package app.mettle.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mettle.MettleApplication
import app.mettle.data.db.DayState
import app.mettle.data.remote.Backend
import app.mettle.tracking.Body
import app.mettle.tracking.Capabilities
import app.mettle.tracking.DayMath
import app.mettle.tracking.TrackingService
import app.mettle.tracking.TrackingState
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
private data class DailySummaryRow(
    @SerialName("user_id") val userId: String,
    val day: String,
    val timezone: String,
    val steps: Int,
    @SerialName("distance_m") val distanceM: Int,
    @SerialName("active_min") val activeMin: Int,
    @SerialName("walking_min") val walkingMin: Int,
    @SerialName("running_min") val runningMin: Int,
    @SerialName("still_min") val stillMin: Int,
    @SerialName("vehicle_min") val vehicleMin: Int,
    @SerialName("active_calories") val activeCalories: Int,
    @SerialName("tracked_min") val trackedMin: Int,
)

@Serializable
private data class SegmentRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    val state: String,
    val steps: Int,
)

@Serializable
private data class StatusRow(
    @SerialName("user_id") val userId: String,
    val location: String?,
    @SerialName("altitude_m") val altitudeM: Double?,
    @SerialName("accuracy_m") val accuracyM: Float?,
    val state: String,
    @SerialName("battery_pct") val batteryPct: Int?,
    val charging: Boolean?,
    @SerialName("tracking_ok") val trackingOk: Boolean,
    val problem: String?,
    @SerialName("position_source") val positionSource: String?,
)

@Serializable
private data class ActivityRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val type: String,
    @SerialName("sub_type") val subType: String,
    val title: String,
    val description: String?,
    @SerialName("started_at") val startedAt: String,
    val timezone: String,
    @SerialName("elapsed_s") val elapsedS: Int,
    @SerialName("moving_s") val movingS: Int,
    @SerialName("distance_m") val distanceM: Int,
    @SerialName("elev_gain_m") val elevGainM: Double,
    @SerialName("elev_loss_m") val elevLossM: Double,
    @SerialName("floors_up") val floorsUp: Int,
    @SerialName("floors_down") val floorsDown: Int,
    @SerialName("avg_pace_s_km") val avgPaceSKm: Double?,
    @SerialName("gap_s_km") val gapSKm: Double?,
    @SerialName("avg_cadence_spm") val avgCadence: Int?,
    val calories: Int?,
    val rpe: Int?,
    @SerialName("training_load") val trainingLoad: Int?,
    val visibility: String,
    val route: String?,
    @SerialName("route_public") val routePublic: String?,
    @SerialName("streams_path") val streamsPath: String?,
    val device: String?,
    @SerialName("deleted_at") val deletedAt: String?,
)

@Serializable
private data class LapRow(
    val id: String, @SerialName("activity_id") val activityId: String, @SerialName("user_id") val userId: String,
    val idx: Int, val kind: String, @SerialName("start_offset_s") val startOffsetS: Int, @SerialName("end_offset_s") val endOffsetS: Int,
    @SerialName("distance_m") val distanceM: Int, @SerialName("moving_s") val movingS: Int, @SerialName("elev_gain_m") val elevGainM: Double,
    @SerialName("floors_up") val floorsUp: Int, @SerialName("floors_down") val floorsDown: Int,
    @SerialName("climb_s") val climbS: Int?, @SerialName("descent_s") val descentS: Int?, @SerialName("rest_s") val restS: Int?,
)

@Serializable
private data class EffortRow(
    val id: String, @SerialName("user_id") val userId: String, @SerialName("activity_id") val activityId: String,
    val effort: String, @SerialName("time_s") val timeS: Int, @SerialName("start_offset_s") val startOffsetS: Int,
    @SerialName("end_offset_s") val endOffsetS: Int, @SerialName("rank_at_time") val rankAtTime: Int?, @SerialName("achieved_at") val achievedAt: String,
)

private fun lineString(points: List<Pair<Double, Double>>): String? =
    if (points.size < 2) null else "SRID=4326;LINESTRING(" + points.joinToString(",") { "${it.second} ${it.first}" } + ")"

@Serializable
private data class CapabilitiesRow(@SerialName("phone_capabilities") val capabilities: JsonObject)

/**
 * Uploads daily totals, the day timeline and live status. Runs every 15 minutes
 * when online (Android's minimum), and right after the app opens.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val auth = Backend.client.auth
        auth.awaitInitialization()
        val userId = auth.currentUserOrNull()?.id ?: return Result.success()   // signed out: nothing to sync
        val db = (applicationContext as MettleApplication).database
        val prefs = applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val zone = ZoneId.systemDefault()
        val body = ProfileCache.body(applicationContext)

        return runCatching {
            val since = prefs.getLong("days_synced_at", 0L)
            val startedAt = System.currentTimeMillis()
            val days = (db.day().daysChangedSince(since) + LocalDate.now(zone).toString()).distinct()
            for (day in days) {
                val minutes = db.day().minutesNow(day)
                if (minutes.isEmpty()) continue
                val s = DayMath.summary(minutes, body)
                Backend.client.from("daily_summaries").upsert(
                    DailySummaryRow(userId, day, zone.id, s.steps, s.distanceM, s.activeMin, s.walkingMin, s.runningMin,
                        s.stillMin, s.vehicleMin, s.activeCalories, s.trackedMin),
                ) { onConflict = "user_id,day" }

                // Replace that day's timeline with the freshly computed one.
                val date = LocalDate.parse(day)
                val dayStart = date.atStartOfDay(zone).toInstant()
                Backend.client.from("day_segments").delete {
                    filter {
                        eq("user_id", userId)
                        gte("start_at", dayStart.toString())
                        lt("start_at", date.plusDays(1).atStartOfDay(zone).toInstant().toString())
                    }
                }
                val rows = DayMath.segments(minutes).map { seg ->
                    SegmentRow(
                        id = UUID.nameUUIDFromBytes("$userId/$day/${seg.startMinute}".toByteArray()).toString(),
                        userId = userId,
                        startAt = dayStart.plusSeconds(seg.startMinute * 60L).toString(),
                        endAt = dayStart.plusSeconds(seg.endMinute * 60L).toString(),
                        state = seg.state.toServer(),
                        steps = seg.steps,
                    )
                }
                if (rows.isNotEmpty()) Backend.client.from("day_segments").insert(rows)
            }
            prefs.edit().putLong("days_synced_at", startedAt).apply()

            // Finished workouts.
            for (a in db.activities().dirty()) {
                val streams = app.mettle.workout.WorkoutSaver.readStreams(applicationContext, a.id)
                var streamsPath: String? = null
                val file = app.mettle.workout.WorkoutSaver.streamsFile(applicationContext, a.id)
                if (file.exists()) {
                    streamsPath = "$userId/${a.id}.json.gz"
                    Backend.client.storage.from("streams").upload(streamsPath, file.readBytes()) { upsert = true }
                }
                val route = a.routePolyline?.let { app.mettle.workout.Polyline.decode(it) }.orEmpty()
                // Hide the first and last 200 m from the group (usually home).
                val public = streams?.let { st ->
                    val total = st.dist.lastOrNull() ?: 0.0
                    st.dist.indices.filter { st.dist[it] > 200 && st.dist[it] < total - 200 }.let { idx ->
                        val keep = app.mettle.workout.WorkoutMath.simplify(idx.map { st.lat[it] }, idx.map { st.lon[it] })
                        keep.map { st.lat[idx[it]] to st.lon[idx[it]] }
                    }
                }.orEmpty()
                Backend.client.from("activities").upsert(
                    ActivityRow(
                        a.id, userId, a.type.name.lowercase(), a.subType.name.lowercase(), a.title, a.description,
                        Instant.ofEpochMilli(a.startedAt).toString(), a.timezone, a.elapsedS, a.movingS, a.distanceM,
                        a.elevGainM, a.elevLossM, a.floorsUp, a.floorsDown, a.avgPaceSKm, a.gapSKm, a.avgCadenceSpm, a.calories,
                        a.rpe, a.trainingLoad, a.visibility.name.lowercase(), lineString(route), lineString(public), streamsPath, a.device,
                        a.deletedAt?.let { Instant.ofEpochMilli(it).toString() },
                    ),
                ) { onConflict = "id" }
                val laps = db.laps().forActivityNow(a.id)
                if (laps.isNotEmpty()) Backend.client.from("laps").upsert(laps.map { l ->
                    LapRow(l.id, a.id, userId, l.idx, l.kind.name.lowercase(), l.startOffsetS, l.endOffsetS, l.distanceM, l.movingS,
                        l.elevGainM, l.floorsUp, l.floorsDown, l.climbS, l.descentS, l.restS)
                }) { onConflict = "id" }
                val efforts = db.bestEfforts().forActivityNow(a.id)
                if (efforts.isNotEmpty()) Backend.client.from("best_efforts").upsert(efforts.map { e ->
                    EffortRow(e.id, userId, a.id, e.effort, e.timeS, e.startOffsetS, e.endOffsetS, e.rankAtTime, Instant.ofEpochMilli(e.achievedAt).toString())
                }) { onConflict = "id" }
                db.activities().markSynced(a.id, a.updatedAt)
            }

            // Live status for the admin map.
            val last = db.track().latest()
            val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val pct = battery?.let { b ->
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) level * 100 / scale else null
            }
            val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 }
            val power = applicationContext.getSystemService(PowerManager::class.java)
            val problem = when {
                !TrackingService.isEnabled(applicationContext) -> "tracking_paused"
                power.isPowerSaveMode -> "battery_saver"
                !power.isIgnoringBatteryOptimizations(applicationContext.packageName) -> "battery_optimised"
                else -> null
            }
            Backend.client.from("member_status").upsert(
                StatusRow(
                    userId = userId,
                    location = last?.let { "SRID=4326;POINT(${it.lon} ${it.lat})" },
                    altitudeM = last?.altitudeM, accuracyM = last?.accuracyM,
                    state = TrackingState.sensorState.toServer(),
                    batteryPct = pct, charging = charging,
                    trackingOk = problem == null, problem = problem,
                    positionSource = last?.let { if ((it.accuracyM ?: 99f) <= 20f) "gps" else "network" },
                ),
            ) { onConflict = "user_id" }

            // Phone capabilities: once per app version.
            val capKey = "caps_${app.mettle.BuildConfig.VERSION_CODE}"
            if (!prefs.getBoolean(capKey, false)) {
                Backend.client.from("profiles").update(CapabilitiesRow(Capabilities.detect(applicationContext))) { filter { eq("id", userId) } }
                prefs.edit().putBoolean(capKey, true).apply()
            }

            // Keep 35 days of minutes and 35 days of positions on the phone.
            val cutoff = LocalDate.now(zone).minusDays(35)
            db.day().deleteBefore(cutoff.toString())
            db.track().deleteBefore(cutoff.atStartOfDay(zone).toInstant().toEpochMilli())
            Result.success()
        }.getOrElse { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "sync", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
            )
            wm.enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build())
        }
    }
}

private fun DayState.toServer() = when (this) {
    DayState.STILL -> "still"; DayState.WALKING -> "walking"; DayState.RUNNING -> "running"
    DayState.VEHICLE -> "vehicle"; DayState.CYCLING -> "cycling"; DayState.UNKNOWN -> "unknown"
}

/** Profile kept on the phone so the app opens (and tracks) without waiting for the server. */
object ProfileCache {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun saveProfile(context: Context, profile: app.mettle.data.remote.Profile) {
        context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            .putString("json", json.encodeToString(app.mettle.data.remote.Profile.serializer(), profile)).apply()
        save(context, profile.heightCm, profile.weightKg, profile.stepGoal)
    }
    fun profile(context: Context, userId: String): app.mettle.data.remote.Profile? =
        context.getSharedPreferences("profile", Context.MODE_PRIVATE).getString("json", null)
            ?.let { runCatching { json.decodeFromString(app.mettle.data.remote.Profile.serializer(), it) }.getOrNull() }
            ?.takeIf { it.id == userId }
    fun clear(context: Context) = context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit().remove("json").apply()

    fun save(context: Context, heightCm: Int?, weightKg: Double?, stepGoal: Int) {
        context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            .putInt("height", heightCm ?: 170).putFloat("weight", (weightKg ?: 70.0).toFloat()).putInt("goal", stepGoal).apply()
    }
    fun body(context: Context): Body {
        val p = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
        return Body(p.getInt("height", 170), p.getFloat("weight", 70f).toDouble())
    }
    fun stepGoal(context: Context): Int = context.getSharedPreferences("profile", Context.MODE_PRIVATE).getInt("goal", 8000)
}
