package app.mettle.workout

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One raw reading during a workout. */
data class Sample(
    val tMs: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val pressureHpa: Float?,
    val steps: Int?,          // cumulative steps since start
)

data class Split(val index: Int, val distanceM: Int, val movingS: Int, val elevChangeM: Double)
data class Effort(val name: String, val timeS: Int, val startOffsetS: Int, val endOffsetS: Int)

/** Everything computed from a workout's samples. */
data class WorkoutResult(
    val elapsedS: Int,
    val movingS: Int,
    val distanceM: Int,
    val elevGainM: Double,
    val elevLossM: Double,
    val avgPaceSKm: Double?,
    val gapSKm: Double?,
    val avgCadence: Int?,
    val splits: List<Split>,
    val efforts: List<Effort>,
    /** Per kept point: offset seconds, lat, lon, cumulative distance, smoothed altitude, moving flag. */
    val t: List<Int>, val lat: List<Double>, val lon: List<Double>,
    val dist: List<Double>, val alt: List<Double?>, val moving: List<Boolean>,
)

/** Pure workout analysis (no Android dependencies; unit-tested). */
object WorkoutMath {
    const val MAX_ACCURACY_M = 25f
    const val MOVING_SPEED = 0.6          // m/s — below this you're standing still
    const val AUTO_PAUSE_AFTER_S = 5

    val EFFORTS = listOf("run_400m" to 400, "run_1k" to 1000, "run_1mi" to 1609, "run_5k" to 5000, "run_10k" to 10000, "run_half" to 21097)

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** Height from air pressure (barometer phones). */
    fun pressureAltitudeM(hpa: Float): Double = 44330.0 * (1 - (hpa / 1013.25).pow(1 / 5.255))

    /** Minetti energy cost of running on a grade (J/kg/m); used for grade-adjusted pace. */
    fun minettiCost(grade: Double): Double {
        val g = grade.coerceIn(-0.45, 0.45)
        return 155.4 * g.pow(5) - 30.4 * g.pow(4) - 43.3 * g.pow(3) + 46.3 * g.pow(2) + 19.5 * g + 3.6
    }

    fun analyse(samples: List<Sample>): WorkoutResult {
        val sorted = samples.sortedBy { it.tMs }
        val start = sorted.firstOrNull()?.tMs ?: 0L
        val end = sorted.lastOrNull()?.tMs ?: 0L
        val pts = sorted.filter { it.lat != null && it.lon != null && (it.accuracyM ?: 0f) <= MAX_ACCURACY_M }
        val useBaro = pts.count { it.pressureHpa != null } > pts.size / 2

        val t = ArrayList<Int>(); val lat = ArrayList<Double>(); val lon = ArrayList<Double>()
        val dist = ArrayList<Double>(); val alt = ArrayList<Double?>(); val moving = ArrayList<Boolean>()
        var total = 0.0
        var stillSince: Long? = null
        var smoothAlt: Double? = null
        for ((i, p) in pts.withIndex()) {
            val rawAlt = if (useBaro) p.pressureHpa?.let { pressureAltitudeM(it) } else p.altitudeM
            smoothAlt = when {
                rawAlt == null -> smoothAlt
                smoothAlt == null -> rawAlt
                else -> smoothAlt + 0.25 * (rawAlt - smoothAlt)
            }
            var isMoving = true
            if (i > 0) {
                val prev = pts[i - 1]
                val d = haversineM(prev.lat!!, prev.lon!!, p.lat!!, p.lon!!)
                val dt = (p.tMs - prev.tMs) / 1000.0
                val speed = if (dt > 0) d / dt else 0.0
                if (speed < MOVING_SPEED) {
                    if (stillSince == null) stillSince = prev.tMs
                    if ((p.tMs - stillSince!!) / 1000 >= AUTO_PAUSE_AFTER_S) isMoving = false
                } else stillSince = null
                // Ignore GPS jitter while standing still, and impossible jumps.
                if (isMoving && speed < 12.5) total += d
            }
            t += ((p.tMs - start) / 1000).toInt(); lat += p.lat!!; lon += p.lon!!
            dist += total; alt += smoothAlt; moving += isMoving
        }

        var movingS = 0
        for (i in 1 until t.size) {
            val dt = t[i] - t[i - 1]
            if (moving[i] && dt <= 15) movingS += dt
        }
        if (pts.isEmpty()) movingS = ((end - start) / 1000).toInt()

        // Elevation with a dead band, so sensor noise doesn't add fake climbing.
        val band = if (useBaro) 1.0 else 4.0
        var gain = 0.0; var loss = 0.0; var ref: Double? = null
        for (a in alt) {
            if (a == null) continue
            if (ref == null) { ref = a; continue }
            val diff = a - ref
            if (diff >= band) { gain += diff; ref = a } else if (diff <= -band) { loss -= diff; ref = a }
        }

        // Km splits (moving time).
        val splits = ArrayList<Split>()
        var nextKm = 1000.0; var splitStartMoving = 0; var cumMoving = 0; var splitStartAlt = alt.firstOrNull { it != null }
        for (i in 1 until t.size) {
            val dt = t[i] - t[i - 1]
            if (moving[i] && dt <= 15) cumMoving += dt
            if (dist[i] >= nextKm) {
                splits += Split(splits.size + 1, 1000, cumMoving - splitStartMoving, (alt[i] ?: 0.0) - (splitStartAlt ?: alt[i] ?: 0.0))
                splitStartMoving = cumMoving; splitStartAlt = alt[i]; nextKm += 1000.0
            }
        }
        val rest = total - (nextKm - 1000.0)
        if (rest >= 100 && t.isNotEmpty()) splits += Split(splits.size + 1, rest.roundToInt(), cumMoving - splitStartMoving, (alt.last() ?: 0.0) - (splitStartAlt ?: alt.last() ?: 0.0))

        // Best efforts: fastest time to cover each distance anywhere in the workout.
        val efforts = ArrayList<Effort>()
        for ((name, target) in EFFORTS) {
            if (total < target) continue
            var best: Effort? = null
            var j = 0
            for (i in t.indices) {
                while (j < t.size && dist[j] - dist[i] < target) j++
                if (j >= t.size) break
                val time = t[j] - t[i]
                if (best == null || time < best.timeS) best = Effort(name, time, t[i], t[j])
            }
            best?.let { efforts += it }
        }

        // Grade-adjusted pace over ~50 m windows.
        var gapSec = 0.0; var gapDist = 0.0; var k = 0
        for (i in t.indices) {
            while (k < t.size && dist[k] - dist[i] < 50) k++
            if (k >= t.size) break
            val d = dist[k] - dist[i]; val dt = (t[k] - t[i]).toDouble()
            val a0 = alt[i]; val a1 = alt[k]
            if (d <= 0 || dt <= 0 || !moving[k]) continue
            val grade = if (a0 != null && a1 != null) (a1 - a0) / d else 0.0
            val factor = minettiCost(grade) / minettiCost(0.0)
            gapDist += d * factor; gapSec += dt
        }

        val stepSamples = sorted.mapNotNull { s -> s.steps?.let { s.tMs to it } }
        val cadence = if (stepSamples.size >= 2 && movingS > 0) {
            ((stepSamples.last().second - stepSamples.first().second) * 60.0 / movingS).roundToInt().takeIf { it in 30..250 }
        } else null

        return WorkoutResult(
            elapsedS = ((end - start) / 1000).toInt(),
            movingS = movingS,
            distanceM = total.roundToInt(),
            elevGainM = (gain * 10).roundToInt() / 10.0,
            elevLossM = (loss * 10).roundToInt() / 10.0,
            avgPaceSKm = if (total >= 50) movingS / (total / 1000.0) else null,
            gapSKm = if (gapDist >= 50) gapSec / (gapDist / 1000.0) else null,
            avgCadence = cadence,
            splits = splits, efforts = efforts,
            t = t, lat = lat, lon = lon, dist = dist, alt = alt, moving = moving,
        )
    }

    /** Douglas–Peucker simplification for the route sent to the server (indices kept). */
    fun simplify(lat: List<Double>, lon: List<Double>, toleranceM: Double = 5.0): List<Int> {
        if (lat.size <= 2) return lat.indices.toList()
        val keep = BooleanArray(lat.size); keep[0] = true; keep[lat.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.add(0 to lat.lastIndex)
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat.average()))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            val ax = lon[a] * mPerDegLon; val ay = lat[a] * mPerDegLat
            val bx = lon[b] * mPerDegLon; val by = lat[b] * mPerDegLat
            for (i in a + 1 until b) {
                val px = lon[i] * mPerDegLon; val py = lat[i] * mPerDegLat
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val d = if (len2 == 0.0) sqrt((px - ax).pow(2) + (py - ay).pow(2))
                    else abs(dy * px - dx * py + bx * ay - by * ax) / sqrt(len2)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > toleranceM && idx > 0) { keep[idx] = true; stack.add(a to idx); stack.add(idx to b) }
        }
        return keep.indices.filter { keep[it] }
    }
}
