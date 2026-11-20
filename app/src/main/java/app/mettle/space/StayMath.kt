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

    /**
     * Stays: stretches of time spent within [STAY_RADIUS_M] of one spot for at
     * least [MIN_STAY_MS]. Points with poor accuracy (> 150 m) are ignored.
     */
    fun stays(points: List<TrackPt>): List<Stay> {
        val pts = points.filter { (it.accuracyM ?: 0f) <= 150f }.sortedBy { it.tMs }
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
            if (last != null && WorkoutMath.haversineM(last.lat, last.lon, s.lat, s.lon) <= STAY_RADIUS_M && s.startMs - last.endMs < 20 * 60_000L) {
                merged[merged.lastIndex] = last.copy(endMs = s.endMs)
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
        places.firstOrNull { WorkoutMath.haversineM(it.lat, it.lon, stay.lat, stay.lon) <= 120 }?.label
}
