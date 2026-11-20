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
        val samples = raw.filter { !it.paused }.map { Sample(it.tMs, it.lat, it.lon, it.accuracyM, it.altitudeM, it.pressureHpa, it.steps) }
        val r = WorkoutMath.analyse(samples)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val elapsed = raw.lastOrNull()?.let { ((it.tMs - startedAt) / 1000).toInt() } ?: r.elapsedS

        GZIPOutputStream(streamsFile(context, id).outputStream()).bufferedWriter().use {
            it.write(json.encodeToString(Streams.serializer(), Streams(1, r.t, r.lat, r.lon, r.dist, r.alt, r.moving)))
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
