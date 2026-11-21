package app.mettle.workout

import android.content.Context
import app.mettle.MettleApplication
import app.mettle.data.db.ActivityEntity
import app.mettle.data.db.ActivitySub
import app.mettle.data.db.ActivityType
import app.mettle.data.db.BestEffortEntity
import app.mettle.data.db.LapEntity
import app.mettle.data.db.LapKind
import app.mettle.data.db.SyncState
import app.mettle.data.db.Visibility
import app.mettle.sync.ProfileCache
import app.mettle.sync.SyncWorker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/** Per-second data of one activity, stored as gzip JSON on the phone and on the server. */
@Serializable
data class Streams(
    val v: Int = 1,
    val t: List<Int>, val lat: List<Double>, val lon: List<Double>,
    val dist: List<Double>, val alt: List<Double?>, val moving: List<Boolean>,
    val hr: List<Int?>? = null,     // heart rate per point (strap/watch), when one was connected
)

data class SaveDetails(val title: String, val description: String?, val rpe: Int?, val visibility: Visibility)

object WorkoutSaver {
    private val json = Json { ignoreUnknownKeys = true }

    fun defaultTitle(type: ActivityType, startedAt: Long): String {
        val hour = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).hour
        val part = when (hour) { in 5..11 -> "Morning"; in 12..16 -> "Afternoon"; in 17..20 -> "Evening"; else -> "Night" }
        val what = when (type) { ActivityType.RUN -> "Run"; ActivityType.WALK -> "Walk"; ActivityType.HIKE -> "Hike"; ActivityType.STAIRS -> "Stair Session" }
        return "$part $what"
    }

    fun streamsFile(context: Context, activityId: String) = File(File(context.filesDir, "streams").apply { mkdirs() }, "$activityId.json.gz")

    fun readStreams(context: Context, activityId: String): Streams? = runCatching {
        GZIPInputStream(streamsFile(context, activityId).inputStream()).bufferedReader().use { json.decodeFromString(Streams.serializer(), it.readText()) }
    }.getOrNull()

    /** Analyses a recorded session and stores it as a workout. Returns the new activity id. */
    suspend fun save(context: Context, sessionId: String, type: ActivityType, startedAt: Long, details: SaveDetails): String {
        val db = (context.applicationContext as MettleApplication).database
        val raw = db.recording().samples(sessionId)
        if (type == ActivityType.STAIRS) return saveStairs(context, sessionId, startedAt, details, raw)
        // Each manual pause starts a new segment, so distance is never bridged across a pause.
        var seg = 0; var wasPaused = false
        val samples = raw.mapNotNull { r ->
            if (r.paused) { wasPaused = true; null }
            else { if (wasPaused) { seg++; wasPaused = false }; Sample(r.tMs, r.lat, r.lon, r.accuracyM, r.altitudeM, r.pressureHpa, r.steps, r.speedMs, seg) }
        }
        val r = WorkoutMath.analyse(samples)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val elapsed = raw.lastOrNull()?.let { ((it.tMs - startedAt) / 1000).toInt() } ?: r.elapsedS

        GZIPOutputStream(streamsFile(context, id).outputStream()).bufferedWriter().use {
            // Heart rate per kept point: nearest strap reading within 5 s.
            val hrRows = raw.filter { it.heartRate != null }.map { it.tMs to it.heartRate!! }
            val hr = if (hrRows.size < 10) null else {
                var j = 0
                r.t.map { off ->
                    val tMs = (samples.firstOrNull()?.tMs ?: startedAt) + off * 1000L
                    while (j + 1 < hrRows.size && kotlin.math.abs(hrRows[j + 1].first - tMs) <= kotlin.math.abs(hrRows[j].first - tMs)) j++
                    hrRows[j].takeIf { kotlin.math.abs(it.first - tMs) <= 5000 }?.second
                }
            }
            it.write(json.encodeToString(Streams.serializer(), Streams(2, r.t, r.lat, r.lon, r.dist, r.alt, r.moving, hr)))
        }
        val keep = WorkoutMath.simplify(r.lat, r.lon)
        val polyline = Polyline.encode(keep.map { r.lat[it] to r.lon[it] })
        val body = ProfileCache.body(context)
        val calories = (body.weightKg * r.distanceM / 1000.0 * if (type == ActivityType.RUN) 1.0 else 0.6).roundToInt()

        db.activities().upsert(
            ActivityEntity(
                id = id, userId = null, type = type, subType = ActivitySub.OUTDOOR,
                title = details.title.ifBlank { defaultTitle(type, startedAt) }, description = details.description?.ifBlank { null },
                startedAt = startedAt, timezone = ZoneId.systemDefault().id,
                elapsedS = elapsed, movingS = r.movingS, distanceM = r.distanceM,
                elevGainM = r.elevGainM, elevLossM = r.elevLossM,
                avgPaceSKm = r.avgPaceSKm, gapSKm = r.gapSKm, avgCadenceSpm = r.avgCadence, calories = calories,
                avgHr = raw.mapNotNull { it.heartRate }.takeIf { it.size > 10 }?.average()?.roundToInt(),
                maxHr = raw.mapNotNull { it.heartRate }.maxOrNull(),
                rpe = details.rpe, trainingLoad = details.rpe?.let { it * r.movingS / 60 },
                visibility = details.visibility, routePolyline = polyline, streamsFile = streamsFile(context, id).name,
                device = android.os.Build.MODEL, createdAt = now, updatedAt = now, syncState = SyncState.DIRTY,
            ),
        )
        db.laps().upsertAll(r.splits.map { s ->
            LapEntity(UUID.randomUUID().toString(), id, s.index, LapKind.AUTO_KM, 0, 0, s.distanceM, s.movingS, s.elevChangeM)
        })
        val efforts = r.efforts.map { e ->
            val previous = db.bestEfforts().best(e.name)
            BestEffortEntity(UUID.randomUUID().toString(), id, e.name, e.timeS, e.startOffsetS, e.endOffsetS,
                rankAtTime = if (previous == null || e.timeS < previous) 1 else null, achievedAt = startedAt)
        }
        db.bestEfforts().upsertAll(efforts)
        db.recording().clear(sessionId)
        WorkoutService.clearSession(context)
        SyncWorker.schedule(context)
        return id
    }

    private suspend fun saveStairs(context: Context, sessionId: String, startedAt: Long, details: SaveDetails,
                                   raw: List<app.mettle.data.db.RecordingSampleEntity>): String {
        val db = (context.applicationContext as MettleApplication).database
        val prefs = context.getSharedPreferences("workout", Context.MODE_PRIVATE)
        val machine = prefs.getBoolean("machine", false)
        val place = prefs.getString("place", null)?.let { db.places().get(it) }
        val spf = place?.stepsPerFloor ?: if (machine) 16 else 22
        val fh = place?.floorHeightM ?: 3.0
        val live = raw.filter { !it.paused }
        val steps = ArrayList<Long>(); var prev = 0
        for (r in live) { val c = r.steps ?: prev; repeat((c - prev).coerceIn(0, 50)) { steps += r.tMs }; prev = maxOf(prev, c) }
        val alt = live.mapNotNull { r -> r.pressureHpa?.let { r.tMs to WorkoutMath.pressureAltitudeM(it) } }
        val laps = live.filter { it.lap }.map { it.tMs }
        val r = app.mettle.stairs.StairMath.analyse(steps, alt.takeIf { it.size > 3 }, spf, fh, laps, machine)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val end = raw.lastOrNull()?.tMs ?: now
        val elapsed = ((end - startedAt) / 1000).toInt()
        val active = r.bouts.sumOf { ((it.endMs - it.startMs) / 1000).toInt() }
        val body = ProfileCache.body(context)
        db.activities().upsert(
            ActivityEntity(
                id = id, userId = null, type = ActivityType.STAIRS,
                subType = if (machine) ActivitySub.STAIR_MACHINE else ActivitySub.STAIRWELL,
                title = details.title.ifBlank { defaultTitle(ActivityType.STAIRS, startedAt) }, description = details.description?.ifBlank { null },
                startedAt = startedAt, timezone = ZoneId.systemDefault().id, elapsedS = elapsed, movingS = active,
                elevGainM = r.floorsUp * fh, elevLossM = r.floorsDown * fh, floorsUp = r.floorsUp, floorsDown = r.floorsDown,
                // Climbing ≈ 1.1 kcal per kg per 100 m up; descending about a third of that.
                calories = (body.weightKg * (r.floorsUp * fh * 0.011 + r.floorsDown * fh * 0.004)).roundToInt(),
                rpe = details.rpe, trainingLoad = details.rpe?.let { it * elapsed / 60 },
                avgHr = raw.mapNotNull { it.heartRate }.takeIf { it.size > 10 }?.average()?.roundToInt(),
                maxHr = raw.mapNotNull { it.heartRate }.maxOrNull(),
                placeId = place?.id, visibility = details.visibility, device = android.os.Build.MODEL,
                createdAt = now, updatedAt = now, syncState = SyncState.DIRTY,
            ),
        )
        db.laps().upsertAll(r.sets.map { st ->
            LapEntity(UUID.randomUUID().toString(), id, st.index, LapKind.STAIR_SET, ((st.startMs - startedAt) / 1000).toInt(),
                ((st.endMs - startedAt) / 1000).toInt(), floorsUp = st.floorsUp, floorsDown = st.floorsDown,
                climbS = st.climbS, descentS = st.descentS, restS = st.restS, movingS = st.climbS + st.descentS)
        })
        db.bestEfforts().upsertAll(r.efforts.map { e ->
            val previous = db.bestEfforts().best(e.name)
            BestEffortEntity(UUID.randomUUID().toString(), id, e.name, e.timeS, ((e.startMs - startedAt) / 1000).toInt(),
                ((e.endMs - startedAt) / 1000).toInt(), if (previous == null || e.timeS < previous) 1 else null, startedAt)
        })
        db.recording().clear(sessionId)
        WorkoutService.clearSession(context)
        SyncWorker.schedule(context)
        return id
    }

    /** Saves a workout from an imported file (GPX). */
    suspend fun saveImported(context: Context, samples: List<Sample>, type: ActivityType, title: String): String {
        val db = (context.applicationContext as MettleApplication).database
        val r = WorkoutMath.analyse(samples)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val startedAt = samples.minOf { it.tMs }
        GZIPOutputStream(streamsFile(context, id).outputStream()).bufferedWriter().use {
            it.write(json.encodeToString(Streams.serializer(), Streams(2, r.t, r.lat, r.lon, r.dist, r.alt, r.moving)))
        }
        val keep = WorkoutMath.simplify(r.lat, r.lon)
        db.activities().upsert(ActivityEntity(
            id = id, userId = null, type = type, title = title, startedAt = startedAt, timezone = ZoneId.systemDefault().id,
            elapsedS = r.elapsedS, movingS = r.movingS, distanceM = r.distanceM, elevGainM = r.elevGainM, elevLossM = r.elevLossM,
            avgPaceSKm = r.avgPaceSKm, gapSKm = r.gapSKm, routePolyline = Polyline.encode(keep.map { r.lat[it] to r.lon[it] }),
            streamsFile = streamsFile(context, id).name, source = "gpx_import", visibility = Visibility.PRIVATE,
            createdAt = now, updatedAt = now, syncState = SyncState.DIRTY,
        ))
        db.laps().upsertAll(r.splits.map { s -> LapEntity(UUID.randomUUID().toString(), id, s.index, LapKind.AUTO_KM, 0, 0, s.distanceM, s.movingS, s.elevChangeM) })
        db.bestEfforts().upsertAll(r.efforts.map { e ->
            val previous = db.bestEfforts().best(e.name)
            BestEffortEntity(UUID.randomUUID().toString(), id, e.name, e.timeS, e.startOffsetS, e.endOffsetS, if (previous == null || e.timeS < previous) 1 else null, startedAt)
        })
        return id
    }

    suspend fun discard(context: Context, sessionId: String) {
        (context.applicationContext as MettleApplication).database.recording().clear(sessionId)
        WorkoutService.clearSession(context)
    }
}

/** Google encoded polyline (compact route storage). */
object Polyline {
    fun encode(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder(); var pLat = 0L; var pLon = 0L
        for ((lat, lon) in points) {
            val la = Math.round(lat * 1e5); val lo = Math.round(lon * 1e5)
            enc(la - pLat, sb); enc(lo - pLon, sb); pLat = la; pLon = lo
        }
        return sb.toString()
    }
    private fun enc(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
        sb.append((x + 63).toInt().toChar())
    }
    fun decode(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(); var i = 0; var lat = 0L; var lon = 0L
        fun next(): Long { var shift = 0; var result = 0L; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1 }
        while (i < s.length) { lat += next(); lon += next(); out += lat / 1e5 to lon / 1e5 }
        return out
    }
}
