package app.mettle.stairs

import kotlin.math.abs
import kotlin.math.roundToInt

enum class Direction { UP, DOWN, FLAT, LIFT_UP, LIFT_DOWN }

data class Bout(val startMs: Long, val endMs: Long, val steps: Int, val direction: Direction, val floors: Int)
data class StairSet(val index: Int, val startMs: Long, val endMs: Long, val floorsUp: Int, val floorsDown: Int,
                    val climbS: Int, val descentS: Int, val restS: Int)
data class FloorEvent(val tMs: Long, val up: Boolean)
data class StairEffort(val name: String, val timeS: Int, val startMs: Long, val endMs: Long)
data class StairResult(val floorsUp: Int, val floorsDown: Int, val liftUp: Int, val liftDown: Int, val bouts: List<Bout>,
                       val sets: List<StairSet>, val floors: List<FloorEvent>, val efforts: List<StairEffort>)

/** Pure stair analysis (unit-tested). */
object StairMath {
    const val BOUT_GAP_MS = 8_000L
    val EFFORTS = listOf(Triple("stairs_up_10fl", true, 10), Triple("stairs_up_50fl", true, 50), Triple("stairs_down_15fl", false, 15))

    /**
     * @param stepTimes timestamp of every step
     * @param altitude optional barometric height samples (time, metres) — phones with a barometer
     * @param laps times the user tapped "New set"
     * @param machine stair machine: everything counts as climbing
     */
    fun analyse(
        stepTimes: List<Long>, altitude: List<Pair<Long, Double>>?, stepsPerFloor: Int, floorHeightM: Double,
        laps: List<Long> = emptyList(), machine: Boolean = false,
    ): StairResult {
        val steps = stepTimes.sorted()
        val spf = stepsPerFloor.coerceAtLeast(4)
        // 1. Bouts of continuous stepping, also cut at manual laps.
        val rawBouts = ArrayList<Triple<Long, Long, Int>>()
        var bStart = -1L; var bLast = -1L; var count = 0
        val lapSet = laps.sorted()
        for (t in steps) {
            val crossesLap = bStart >= 0 && lapSet.any { it in (bLast + 1)..t }
            if (bStart < 0) { bStart = t; bLast = t; count = 1; continue }
            if (t - bLast > BOUT_GAP_MS || crossesLap) { rawBouts += Triple(bStart, bLast, count); bStart = t; count = 0 }
            bLast = t; count++
        }
        if (bStart >= 0) rawBouts += Triple(bStart, bLast, count)

        // 2. Direction and floors per bout.
        val bouts = ArrayList<Bout>()
        var expectUp = true
        for ((s, e, n) in rawBouts) {
            val dAlt = altitude?.let { heightChange(it, s - 2000, e + 2000) }
            val (dir, floors) = when {
                machine -> Direction.UP to (n.toDouble() / spf).roundToInt()
                dAlt != null -> when {
                    dAlt >= floorHeightM * 0.6 -> Direction.UP to (dAlt / floorHeightM).roundToInt().coerceAtLeast(1)
                    dAlt <= -floorHeightM * 0.6 -> Direction.DOWN to (-dAlt / floorHeightM).roundToInt().coerceAtLeast(1)
                    else -> Direction.FLAT to 0
                }
                n < spf / 2 -> Direction.FLAT to 0
                else -> (if (expectUp) Direction.UP else Direction.DOWN).also { expectUp = !expectUp } to (n.toDouble() / spf).roundToInt().coerceAtLeast(1)
            }
            bouts += Bout(s, e, n, dir, floors)
        }

        // 3. Lifts: height changed by ≥ 1.5 floors with (almost) no steps in between bouts.
        var liftUp = 0; var liftDown = 0
        if (altitude != null && altitude.size > 2) {
            val gaps = ArrayList<Pair<Long, Long>>()
            var prevEnd = altitude.first().first
            for (b in bouts) { gaps += prevEnd to b.startMs; prevEnd = b.endMs }
            gaps += prevEnd to altitude.last().first
            for ((gs, ge) in gaps) {
                if (ge - gs < 5_000) continue
                val d = heightChange(altitude, gs, ge) ?: continue
                val floors = (abs(d) / floorHeightM).roundToInt()
                if (abs(d) >= floorHeightM * 1.5) {
                    val stepsIn = steps.count { it in gs..ge }
                    if (stepsIn <= 6) { if (d > 0) liftUp += floors else liftDown += floors }
                }
            }
        }

        // 4. Floor events spread across each bout (for efforts and timelines).
        val floors = ArrayList<FloorEvent>()
        for (b in bouts) if (b.direction == Direction.UP || b.direction == Direction.DOWN) {
            for (k in 1..b.floors) floors += FloorEvent(b.startMs + (b.endMs - b.startMs) * k / b.floors, b.direction == Direction.UP)
        }

        // 5. Sets: an up bout plus the following down bout.
        val sets = ArrayList<StairSet>()
        val climbing = bouts.filter { it.direction == Direction.UP || it.direction == Direction.DOWN }
        var i = 0
        while (i < climbing.size) {
            val up = climbing[i].takeIf { it.direction == Direction.UP }
            val down = (if (up != null) climbing.getOrNull(i + 1) else climbing[i])?.takeIf { it.direction == Direction.DOWN }
            val first = up ?: down!!
            val last = down ?: up!!
            val next = climbing.getOrNull(i + (if (up != null && down != null) 2 else 1))
            sets += StairSet(sets.size + 1, first.startMs, last.endMs, up?.floors ?: 0, down?.floors ?: 0,
                up?.let { ((it.endMs - it.startMs) / 1000).toInt() } ?: 0,
                down?.let { ((it.endMs - it.startMs) / 1000).toInt() } ?: 0,
                next?.let { ((next.startMs - last.endMs) / 1000).toInt() } ?: 0)
            i += if (up != null && down != null) 2 else 1
        }

        // 6. Fastest N floors.
        val efforts = ArrayList<StairEffort>()
        for ((name, up, n) in EFFORTS) {
            val ev = floors.filter { it.up == up }
            if (ev.size < n) continue
            var best: StairEffort? = null
            for (k in 0..ev.size - n) {
                val start = if (k == 0) bouts.first { b -> (b.direction == Direction.UP) == up && b.direction != Direction.FLAT }.startMs else ev[k - 1].tMs
                val end = ev[k + n - 1].tMs
                val s = ((end - start) / 1000).toInt()
                if (best == null || s < best.timeS) best = StairEffort(name, s, start, end)
            }
            best?.let { efforts += it }
        }

        return StairResult(floors.count { it.up }, floors.count { !it.up }, liftUp, liftDown, bouts, sets, floors, efforts)
    }

    /** Height just after [toMs] minus height just before [fromMs] (medians of ±4 s, robust to noise). */
    fun heightChange(alt: List<Pair<Long, Double>>, fromMs: Long, toMs: Long): Double? {
        fun around(t: Long): Double? {
            val near = alt.filter { abs(it.first - t) <= 4_000 }.map { it.second }.sorted()
            if (near.isNotEmpty()) return near[near.size / 2]
            return alt.minByOrNull { abs(it.first - t) }?.takeIf { abs(it.first - t) <= 30_000 }?.second
        }
        val a = around(fromMs) ?: return null
        val b = around(toMs) ?: return null
        return b - a
    }
}
