package app.mettle.health

import android.content.Context
import app.mettle.data.remote.Backend
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable private data class SpotRow(val id: String, @SerialName("user_id") val userId: String, @SerialName("taken_at") val takenAt: String,
                                         val bpm: Int, val quality: Double, val context: String)
@Serializable private data class SleepRow(val id: String, @SerialName("user_id") val userId: String, @SerialName("start_at") val startAt: String,
                                          @SerialName("end_at") val endAt: String, @SerialName("asleep_min") val asleep: Int, @SerialName("deep_min") val deep: Int,
                                          @SerialName("light_min") val light: Int, @SerialName("rem_min") val rem: Int, @SerialName("awake_min") val awake: Int)
@Serializable private data class DeviceRow(val id: String, @SerialName("user_id") val userId: String, val kind: String, val name: String,
                                           val address: String?, @SerialName("provides_hr") val hr: Boolean, @SerialName("provides_sleep") val sleep: Boolean,
                                           @SerialName("last_seen_at") val lastSeen: String)
@Serializable private data class RestingRow(@SerialName("user_id") val userId: String, val day: String, val timezone: String, @SerialName("resting_hr") val restingHr: Int)

/** Uploads heart-rate checks, sleep and devices; the admin sees them, others don't. */
object HealthSync {
    suspend fun run(c: Context, userId: String) {
        val checks = HealthStore.unsyncedChecks(c)
        if (checks.isNotEmpty()) {
            Backend.client.from("hr_spot_checks").upsert(checks.map {
                SpotRow(UUID.nameUUIDFromBytes("$userId/hr/${it.tMs}".toByteArray()).toString(), userId, Instant.ofEpochMilli(it.tMs).toString(), it.bpm, it.quality, it.context)
            }) { onConflict = "id" }
            HealthStore.markChecksSynced(c)
        }
        val devices = ArrayList<DeviceRow>()
        val now = Instant.now().toString()
        HeartRateBle.saved(c)?.let { devices += DeviceRow(UUID.nameUUIDFromBytes("$userId/ble/${it.address}".toByteArray()).toString(), userId, "ble_heart_rate", it.name, it.address, true, false, now) }
        if (HealthStore.healthConnectGranted(c)) {
            devices += DeviceRow(UUID.nameUUIDFromBytes("$userId/hc".toByteArray()).toString(), userId, "health_connect", "Health Connect", null, true, true, now)
            val sleeps = HealthStore.recentSleep(c, 7)
            if (sleeps.isNotEmpty()) Backend.client.from("sleep_sessions").upsert(sleeps.map { s ->
                SleepRow(UUID.nameUUIDFromBytes("$userId/sleep/${s.id}".toByteArray()).toString(), userId, s.start.toString(), s.end.toString(),
                    s.asleepMin, s.deepMin, s.lightMin, s.remMin, s.awakeMin)
            }) { onConflict = "id" }
        }
        if (devices.isNotEmpty()) Backend.client.from("devices").upsert(devices) { onConflict = "id" }
        HealthStore.restingHrToday(c)?.let { hr ->
            val zone = ZoneId.systemDefault()
            Backend.client.from("daily_summaries").upsert(RestingRow(userId, LocalDate.now(zone).toString(), zone.id, hr)) { onConflict = "user_id,day" }
        }
    }
}
