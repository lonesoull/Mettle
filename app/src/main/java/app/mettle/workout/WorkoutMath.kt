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
    val speedMs: Float? = null,   // GPS chip (Doppler) speed: far more accurate than position differences
    val segment: Int = 0,         // increases after each manual pause, so paused travel is never counted
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
    const val GATE_MIN_M = 10.0
    const val GATE_MAX_M = 25.0

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

    /**
     * Position smoother (Kalman filter, position-only). Each GPS fix is trusted according to its
     * reported accuracy, which removes the ±3–5 m wobble that inflates distance, without cutting corners.
     */
    private class Smoother(private val qMps: Double) {
        var lat = 0.0; var lon = 0.0; private var variance = -1.0; private var lastT = 0L
        fun reset() { variance = -1.0 }
        fun add(la: Double, lo: Double, accuracy: Double, tMs: Long) {
            val acc = accuracy.coerceAtLeast(1.0)
            if (variance < 0) { lat = la; lon = lo; variance = acc * acc; lastT = tMs; return }
            val dt = (tMs - lastT) / 1000.0
            if (dt > 0) { variance += dt * qMps * qMps; lastT = tMs }
            val k = variance / (variance + acc * acc)
            lat += k * (la - lat); lon += k * (lo - lon)
            variance *= (1 - k)
        }
    }

    fun analyse(samples: List<Sample>): WorkoutResult {
        val sorted = samples.sortedBy { it.tMs }
        val start = sorted.firstOrNull()?.tMs ?: 0L
        val end = sorted.lastOrNull()?.tMs ?: 0L
        // GPS warm-up: skip the first rough fixes until the receiver has a good lock.
        val firstGood = sorted.indexOfFirst { it.lat != null && (it.accuracyM ?: 0f) <= MAX_ACCURACY_M }
        val pts = if (firstGood < 0) emptyList() else
            sorted.drop(firstGood).filter { it.lat != null && it.lon != null && (it.accuracyM ?: 0f) <= MAX_ACCURACY_M }
        val useBaro = pts.count { it.pressureHpa != null } > pts.size / 2
        val hasDoppler = pts.count { it.speedMs != null } > pts.size / 2

        // Altitude: barometer if present, else GPS height averaged over ±15 s (GPS height alone jumps by metres).
        val rawAlt = pts.map { p -> if (useBaro) p.pressureHpa?.let { pressureAltitudeM(it) } else p.altitudeM }
        val smoothAlts = ArrayList<Double?>(pts.size)
        run {
            var lo = 0; var hi = 0; var sum = 0.0; var n = 0
            val win = if (useBaro) 5_000L else 15_000L
            for (i in pts.indices) {
                while (hi < pts.size && pts[hi].tMs <= pts[i].tMs + win) { rawAlt[hi]?.let { sum += it; n++ }; hi++ }
                while (lo < i && pts[lo].tMs < pts[i].tMs - win) { rawAlt[lo]?.let { sum -= it; n-- }; lo++ }
                smoothAlts += if (n > 0) sum / n else null
            }
        }

        val t = ArrayList<Int>(); val lat = ArrayList<Double>(); val lon = ArrayList<Double>()
        val dist = ArrayList<Double>(); val alt = ArrayList<Double?>(); val moving = ArrayList<Boolean>()
        var total = 0.0
        var stillSince: Long? = null
        val smoother = Smoother(3.0)
        // Steps (runs/walks/hikes): if the step counter is recorded but hasn't moved for 8 s, you're standing,
        // whatever the GPS wobble says. Phones without a step sensor simply skip this check.
        val hasSteps = sorted.count { it.steps != null } > sorted.size / 2
        val stepTimes = ArrayList<Pair<Long, Int>>()
        sorted.forEach { s -> s.steps?.let { stepTimes += s.tMs to it } }
        fun stillBySteps(t: Long): Boolean {
            if (!hasSteps) return false
            var lo = 0; var hi = stepTimes.lastIndex; var at = -1
            while (lo <= hi) { val m = (lo + hi) / 2; if (stepTimes[m].first <= t) { at = m; lo = m + 1 } else hi = m - 1 }
            if (at < 0) return false
            val now = stepTimes[at]
            var k = at
            while (k > 0 && stepTimes[k - 1].first >= t - 8_000) k--
            return stepTimes[k].first <= t - 7_000 && stepTimes[k].second == now.second
        }
        var prevLat = 0.0; var prevLon = 0.0; var prevT = 0L; var prevSeg = -1
        // Movement gate (idea from OpenTracks): distance waits as "pending" and only counts once the
        // smoothed position has really moved away from the gate point. If the pending distance grows
        // well beyond how far you actually got, it was noise (standing still) and is thrown away.
        var gateLat = 0.0; var gateLon = 0.0; var pending = 0.0; var gateSet = false
        for ((i, p) in pts.withIndex()) {
            if (p.segment != prevSeg) { smoother.reset(); gateSet = false; pending = 0.0 }   // after a pause: fresh line, no bridging
            smoother.add(p.lat!!, p.lon!!, (p.accuracyM ?: 5f).toDouble(), p.tMs)
            val la = smoother.lat; val lo = smoother.lon
            var isMoving = true
            if (i > 0 && p.segment == prevSeg) {
                val d = haversineM(prevLat, prevLon, la, lo)
                val dt = (p.tMs - prevT) / 1000.0
                val implied = if (dt > 0) d / dt else 0.0
                // Moving or stopped: trust the GPS chip's own speed when it reports one.
                val speed = if (hasDoppler && p.speedMs != null) p.speedMs.toDouble() else implied
                // Steps say "standing" unless the GPS chip itself measures a clear running/riding speed.
                val chipFast = p.speedMs != null && p.speedMs >= 2.0f
                if (speed < MOVING_SPEED || (!chipFast && stillBySteps(p.tMs))) {
                    if (stillSince == null) stillSince = prevT
                    if ((p.tMs - stillSince!!) / 1000 >= AUTO_PAUSE_AFTER_S) isMoving = false
                } else stillSince = null
                // Reject impossible jumps (multipath next to buildings): far faster than the chip says you're going.
                val jump = implied > 12.5 || (hasDoppler && p.speedMs != null && dt <= 5 && implied > p.speedMs * 3 + 4)
                // Distance: integrate the GPS chip's speed (unaffected by position wobble) when available,
                // falling back to the smoothed track across gaps or on phones without it.
                val prevSpeed = pts[i - 1].speedMs
                val step = if (hasDoppler && p.speedMs != null && prevSpeed != null && dt in 0.0..5.0)
                    ((p.speedMs + prevSpeed) / 2.0) * dt else d
                if (isMoving && !jump) pending += step
                if (!gateSet) { gateLat = la; gateLon = lo; gateSet = true }
                val gate = ((p.accuracyM ?: 10f).toDouble()).coerceIn(GATE_MIN_M, GATE_MAX_M)
                val moved = haversineM(gateLat, gateLon, la, lo)
                if (moved >= gate) { total += pending; pending = 0.0; gateLat = la; gateLon = lo }
                else if (pending > 3 * gate) pending = 0.0      // lots of "distance" but no real progress: noise
            } else stillSince = null
            if (!gateSet) { gateLat = la; gateLon = lo; gateSet = true }
            prevLat = la; prevLon = lo; prevT = p.tMs; prevSeg = p.segment
            t += ((p.tMs - start) / 1000).toInt(); lat += la; lon += lo
            dist += total; alt += smoothAlts[i]; moving += isMoving
        }
        // The last few metres before Finish: count them if they were real progress, not jitter.
        if (pending > 0 && lat.isNotEmpty() && haversineM(gateLat, gateLon, lat.last(), lon.last()) >= pending * 0.5) {
            total += pending; dist[dist.lastIndex] = total
        }

        var movingS = 0
        for (i in 1 until t.size) {
            val dt = t[i] - t[i - 1]
            if (moving[i] && dt <= 15) movingS += dt
        }
        if (pts.isEmpty()) movingS = ((end - start) / 1000).toInt()

        // Elevation with a dead band, so sensor noise doesn't add fake climbing.
        val band = if (useBaro) 1.0 else 3.0
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
