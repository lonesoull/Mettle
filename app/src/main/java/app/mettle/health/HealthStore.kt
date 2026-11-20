package app.mettle.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.mettle.MettleApplication
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SpotCheck(val tMs: Long, val bpm: Int, val quality: Double, val context: String)
data class Sleep(val id: String, val start: Instant, val end: Instant, val asleepMin: Int, val deepMin: Int, val lightMin: Int, val remMin: Int, val awakeMin: Int)

/** Heart-rate checks, sleep and readiness on the phone. */
object HealthStore {
    private fun p(c: Context) = c.getSharedPreferences("health", Context.MODE_PRIVATE)

    fun addSpotCheck(c: Context, s: SpotCheck) {
        val a = JSONArray(p(c).getString("checks", "[]"))
        a.put(JSONObject().put("t", s.tMs).put("bpm", s.bpm).put("q", s.quality).put("ctx", s.context).put("synced", false))
        val trimmed = JSONArray(); for (i in maxOf(0, a.length() - 60) until a.length()) trimmed.put(a.get(i))
        p(c).edit().putString("checks", trimmed.toString()).apply()
    }
    fun spotChecks(c: Context): List<SpotCheck> = JSONArray(p(c).getString("checks", "[]")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> SpotCheck(o.getLong("t"), o.getInt("bpm"), o.getDouble("q"), o.getString("ctx")) } }
    }
    internal fun unsyncedChecks(c: Context): List<SpotCheck> = JSONArray(p(c).getString("checks", "[]")).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("synced") }
            .map { o -> SpotCheck(o.getLong("t"), o.getInt("bpm"), o.getDouble("q"), o.getString("ctx")) }
    }
    internal fun markChecksSynced(c: Context) {
        val a = JSONArray(p(c).getString("checks", "[]")); for (i in 0 until a.length()) a.getJSONObject(i).put("synced", true)
        p(c).edit().putString("checks", a.toString()).apply()
    }

    // ---- Health Connect (sleep and resting heart rate written by a watch's own app)
    val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    )
    fun healthConnectAvailable(c: Context) = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE
    suspend fun healthConnectGranted(c: Context): Boolean = healthConnectAvailable(c) &&
        runCatching { HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)

    suspend fun recentSleep(c: Context, days: Long = 3): List<Sleep> {
        if (!healthConnectGranted(c)) return emptyList()
        val client = HealthConnectClient.getOrCreate(c)
        val now = Instant.now()
        val recs = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofDays(days)), now))).records
        return recs.map { r ->
            fun stage(vararg types: Int) = r.stages.filter { it.stage in types }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
            val total = Duration.between(r.startTime, r.endTime).toMinutes().toInt()
            val awake = stage(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
            Sleep(r.metadata.id, r.startTime, r.endTime, if (r.stages.isEmpty()) total else total - awake,
                stage(SleepSessionRecord.STAGE_TYPE_DEEP), stage(SleepSessionRecord.STAGE_TYPE_LIGHT), stage(SleepSessionRecord.STAGE_TYPE_REM), awake)
        }
    }

    suspend fun restingHrToday(c: Context): Int? {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        spotChecks(c).filter { it.tMs >= todayStart.toEpochMilli() && it.context == "morning" }.maxByOrNull { it.tMs }?.let { return it.bpm }
        if (!healthConnectGranted(c)) return null
        return runCatching {
            HealthConnectClient.getOrCreate(c).readRecords(ReadRecordsRequest(RestingHeartRateRecord::class,
                TimeRangeFilter.between(todayStart, Instant.now()))).records.lastOrNull()?.beatsPerMinute?.toInt()
        }.getOrNull()
    }

    /** Readiness for today, or null when there is nothing to base it on. */
    suspend fun readiness(c: Context): Int? {
        val zone = ZoneId.systemDefault()
        val today = restingHrToday(c)
        val morning = spotChecks(c).filter { it.context == "morning" && it.tMs < LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli() }
        val baseline = morning.takeLast(14).map { it.bpm.toDouble() }.takeIf { it.size >= 3 }?.average()
        val sleep = recentSleep(c, 1).maxByOrNull { it.end }?.asleepMin
        val db = (c.applicationContext as MettleApplication).database
        val since = LocalDate.now(zone).minusDays(42)
        val acts = db.activities().betweenNow(since.atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        val perDay = (0..42).map { d ->
            val day = since.plusDays(d.toLong())
            acts.filter { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == day }.sumOf { (it.trainingLoad ?: 0).toDouble() }
        }
        val (acute, chronic) = HealthMath.loads(perDay)
        if (today == null && sleep == null && chronic == 0.0) return null
        return HealthMath.readiness(today, baseline, sleep, acute, chronic)
    }
}
