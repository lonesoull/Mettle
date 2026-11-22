package app.mettle.space

import app.mettle.workout.WorkoutMath
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

data class TrackPt(val tMs: Long, val lat: Double, val lon: Double, val accuracyM: Float?, val altitudeM: Double?)
data class Stay(val startMs: Long, val endMs: Long, val lat: Double, val lon: Double, val accuracyM: Float, val altitudeM: Double?, val label: String? = null)
data class KnownPlace(val label: String, val lat: Double, val lon: Double)

/** Pure location-history maths (unit-tested). */
object StayMath {
    const val STAY_RADIUS_M = 75.0
    const val MIN_STAY_MS = 5 * 60_000L
    const val MAX_DAY_ACCURACY_M = 75f

    /**
     * Cleans a day of location points before drawing or finding places. Indoors and while still,
     * the phone's Wi-Fi/cell positions can be hundreds of metres off and jump back and forth.
     * Returns the indices of points worth keeping:
     *  - drops vague fixes (accuracy worse than [MAX_DAY_ACCURACY_M]),
     *  - drops jumps faster than any vehicle,
     *  - drops "spikes": a point far from both neighbours while those neighbours agree with each other.
     */
    fun clean(points: List<TrackPt>): List<Int> {
        var idx = points.indices.filter { (points[it].accuracyM ?: 0f) <= MAX_DAY_ACCURACY_M }.sortedBy { points[it].tMs }
        fun d(a: Int, b: Int) = WorkoutMath.haversineM(points[a].lat, points[a].lon, points[b].lat, points[b].lon)
        // Impossible speed vs the last kept point (>200 km/h).
        val fast = ArrayList<Int>()
        for (i in idx) {
            val last = fast.lastOrNull()
            if (last == null) { fast += i; continue }
            val dt = (points[i].tMs - points[last].tMs) / 1000.0
            if (dt > 0 && d(last, i) / dt > 55 && d(last, i) > 150) continue
            fast += i
        }
        idx = fast
        repeat(3) {
            if (idx.size < 3) return idx
            val keep = ArrayList<Int>(idx.size); keep += idx.first()
            for (k in 1 until idx.lastIndex) {
                val a = keep.last(); val b = idx[k]; val c = idx[k + 1]
                val tol = maxOf(60.0, (points[b].accuracyM ?: 30f).toDouble())
                val spike = d(a, b) > tol && d(b, c) > tol && d(a, c) < 0.5 * minOf(d(a, b), d(b, c))
                if (!spike) keep += b
            }
            keep += idx.last()
            if (keep.size == idx.size) return keep
            idx = keep
        }
        return idx
    }

    /**
     * Movement must be explained by your body: a new position only counts if the steps you took
     * since the last trusted position could cover the distance (or you were in a vehicle, or the
     * tracker has no step data for that time). Otherwise the point is GPS error and you're kept
     * where you were. [budgetM] returns how far you could have gone between two times, or null
     * for "unknown / unlimited".
     *
     * If several rejected points in a row agree with each other, the trusted position itself was
     * wrong, so it moves to them.
     */
    fun anchorToSteps(points: List<TrackPt>, budgetM: (Long, Long) -> Double?): List<TrackPt> {
        if (points.isEmpty()) return points
        val out = ArrayList<TrackPt>(points.size)
        var anchor = points[0]; out += anchor
        var support = 1                       // how many accepted points back up the current position
        val rejected = ArrayList<TrackPt>()
        for (k in 1 until points.size) {
            val p = points[k]
            val dist = WorkoutMath.haversineM(anchor.lat, anchor.lon, p.lat, p.lon)
            val budget = budgetM(anchor.tMs, p.tMs)
            val slack = 12.0 + minOf(30.0, ((anchor.accuracyM ?: 10f) + (p.accuracyM ?: 10f)).toDouble() / 2)
            if (budget == null || dist <= budget + slack) {
                support = if (dist <= 30) support + 1 else 1
                anchor = p; rejected.clear(); out += p
            } else {
                // Keep only the run of rejected points that agree with this one.
                rejected.removeAll { WorkoutMath.haversineM(p.lat, p.lon, it.lat, it.lon) > 30 }
                rejected += p
                // Switch only if the other spot now has more evidence than where we think you are.
                val agree = rejected.size >= 6 && rejected.size > support && rejected.last().tMs - rejected.first().tMs >= 10 * 60_000L
                if (agree) { anchor = p; support = rejected.size; rejected.clear(); out += p }
                else out += p.copy(lat = anchor.lat, lon = anchor.lon)
            }
        }
        return out
    }

    /**
     * For drawing: while you stay in one spot, wobbling fixes are replaced by that spot's centre,
     * so the map shows a dot instead of a scribble. Movement is kept as recorded.
     */
    fun settle(points: List<TrackPt>): List<TrackPt> {
        val out = ArrayList<TrackPt>(points.size)
        var i = 0
        while (i < points.size) {
            var sumLat = points[i].lat; var sumLon = points[i].lon; var n = 1; var j = i + 1
            // A point is "wobble" only if it's within its own uncertainty of the spot (min 8 m), so
            // sharp GPS or step-tracked movement of a few metres is kept, while vague fixes are pinned.
            while (j < points.size && WorkoutMath.haversineM(sumLat / n, sumLon / n, points[j].lat, points[j].lon) <=
                maxOf(8.0, minOf(STAY_RADIUS_M, (points[j].accuracyM ?: 20f).toDouble()))) {
                sumLat += points[j].lat; sumLon += points[j].lon; n++; j++
            }
            if (n >= 3) for (k in i until j) out += points[k].copy(lat = sumLat / n, lon = sumLon / n) else for (k in i until j) out += points[k]
            i = j
        }
        return out
    }

    /**
     * Stays: stretches of time spent within [STAY_RADIUS_M] of one spot for at
     * least [MIN_STAY_MS]. Points with poor accuracy (> 150 m) are ignored.
     */
    fun stays(points: List<TrackPt>): List<Stay> {
        val pts = clean(points).map { points[it] }
        val out = ArrayList<Stay>()
        var i = 0
        while (i < pts.size) {
            var sumLat = pts[i].lat; var sumLon = pts[i].lon; var n = 1
            var j = i + 1
            while (j < pts.size) {
                val cLat = sumLat / n; val cLon = sumLon / n
                if (WorkoutMath.haversineM(cLat, cLon, pts[j].lat, pts[j].lon) > STAY_RADIUS_M) break
                sumLat += pts[j].lat; sumLon += pts[j].lon; n++; j++
            }
            val endIdx = j - 1
            // A stay also lasts until the next point, if that point comes after a long quiet gap.
            val endMs = if (j < pts.size && pts[j].tMs - pts[endIdx].tMs > 15 * 60_000L) pts[j].tMs else pts[endIdx].tMs
            if (endMs - pts[i].tMs >= MIN_STAY_MS) {
                val group = pts.subList(i, j)
                out += Stay(pts[i].tMs, endMs, sumLat / n, sumLon / n,
                    group.mapNotNull { it.accuracyM }.average().toFloat().takeIf { !it.isNaN() } ?: 50f,
                    group.mapNotNull { it.altitudeM }.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] })
                i = j
            } else i++
        }
        // Merge consecutive stays at the same spot (a short GPS blip in between).
        val merged = ArrayList<Stay>()
        for (s in out) {
            val last = merged.lastOrNull()
            val near = maxOf(120.0, (last?.accuracyM ?: 0f).toDouble() + s.accuracyM)
            if (last != null && WorkoutMath.haversineM(last.lat, last.lon, s.lat, s.lon) <= near && s.startMs - last.endMs < 45 * 60_000L) {
                val wa = (last.endMs - last.startMs).toDouble(); val wb = (s.endMs - s.startMs).toDouble()
                merged[merged.lastIndex] = last.copy(endMs = s.endMs,
                    lat = (last.lat * wa + s.lat * wb) / (wa + wb), lon = (last.lon * wa + s.lon * wb) / (wa + wb))
            } else merged += s
        }
        return merged
    }

    /** Home = where most night time (00:00–06:00) is spent; Work = most weekday daytime elsewhere. */
    fun learnPlaces(stays: List<Stay>, zone: ZoneId): List<KnownPlace> {
        data class Cluster(var lat: Double, var lon: Double, var night: Long = 0, var workday: Long = 0, var n: Int = 0)
        val clusters = ArrayList<Cluster>()
        for (s in stays) {
            val c = clusters.firstOrNull { WorkoutMath.haversineM(it.lat, it.lon, s.lat, s.lon) <= 120 }
                ?: Cluster(s.lat, s.lon).also { clusters += it }
            c.lat = (c.lat * c.n + s.lat) / (c.n + 1); c.lon = (c.lon * c.n + s.lon) / (c.n + 1); c.n++
            var t = s.startMs
            while (t < s.endMs) {
                val z = Instant.ofEpochMilli(t).atZone(zone)
                if (z.hour < 6) c.night += 60_000
                if (z.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && z.hour in 9..17) c.workday += 60_000
                t += 60_000
            }
        }
        val out = ArrayList<KnownPlace>()
        val home = clusters.filter { it.night >= 3 * 3_600_000L }.maxByOrNull { it.night }
        home?.let { out += KnownPlace("Home", it.lat, it.lon) }
        clusters.filter { it !== home && it.workday >= 6 * 3_600_000L }.maxByOrNull { it.workday }?.let { out += KnownPlace("Work", it.lat, it.lon) }
        return out
    }

    fun label(stay: Stay, places: List<KnownPlace>): String? =
        places.firstOrNull { WorkoutMath.haversineM(it.lat, it.lon, stay.lat, stay.lon) <= maxOf(150.0, stay.accuracyM.toDouble()) }?.label
}
