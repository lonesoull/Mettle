package app.mettle.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mettle.MettleApplication
import app.mettle.R
import app.mettle.data.remote.Backend
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.util.UUID

data class Contact(val name: String, val phone: String)

/** Each person's safety settings, kept on the phone so alerts work offline. */
object SafetyPrefs {
    private fun p(c: Context) = c.getSharedPreferences("safety", Context.MODE_PRIVATE)
    fun fallDetection(c: Context) = p(c).getBoolean("fall", true)
    fun setFallDetection(c: Context, on: Boolean) = p(c).edit().putBoolean("fall", on).apply()
    fun noMovement(c: Context) = p(c).getBoolean("nomove", false)
    fun noMovementHours(c: Context) = p(c).getInt("nomove_hours", 4)
    fun noMovementFrom(c: Context): LocalTime = LocalTime.parse(p(c).getString("nomove_from", "07:00"))
    fun noMovementTo(c: Context): LocalTime = LocalTime.parse(p(c).getString("nomove_to", "22:00"))
    fun setNoMovement(c: Context, on: Boolean, hours: Int, from: String, to: String) =
        p(c).edit().putBoolean("nomove", on).putInt("nomove_hours", hours).putString("nomove_from", from).putString("nomove_to", to)
            .putBoolean("settings_dirty", true).apply()
    fun contacts(c: Context): List<Contact> = JSONArray(p(c).getString("contacts", "[]")).let { a ->
        (0 until a.length()).map { Contact(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("phone")) }
    }
    fun setContacts(c: Context, list: List<Contact>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        p(c).edit().putString("contacts", a.toString()).putBoolean("contacts_dirty", true).apply()
    }
    internal fun isDirty(c: Context, key: String) = p(c).getBoolean(key, false)
    internal fun clean(c: Context, key: String) = p(c).edit().putBoolean(key, false).apply()
    internal fun queue(c: Context): JSONArray = JSONArray(p(c).getString("queue", "[]"))
    internal fun setQueue(c: Context, a: JSONArray) = p(c).edit().putString("queue", a.toString()).apply()
    var lastNoMovementAlert: Long = 0
}

/** Live countdown state shared with the "Are you OK?" screen. */
object SafetyState {
    val countdown = MutableStateFlow<Pair<String, Int>?>(null)   // kind to seconds left
    private var job: Job? = null

    /** Shows "Are you OK?" for [seconds]; if nobody cancels, sends the alert. */
    fun startCountdown(context: Context, kind: String, seconds: Int = 30, detail: Map<String, String> = emptyMap()) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        countdown.value = kind to seconds
        showCountdownNotification(app, kind)
        job = CoroutineScope(Dispatchers.Default).launch {
            for (s in seconds downTo 1) { countdown.value = kind to s; delay(1000) }
            countdown.value = null
            app.getSystemService(NotificationManager::class.java).cancel(NOTIF_COUNTDOWN)
            Alerts.send(app, kind, detail)
        }
    }

    fun cancel(context: Context) {
        job?.cancel(); job = null; countdown.value = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_COUNTDOWN)
    }

    private fun showCountdownNotification(c: Context, kind: String) {
        Alerts.channel(c)
        val open = PendingIntent.getActivity(c, 5, Intent(c, AreYouOkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, Alerts.CHANNEL).setSmallIcon(R.drawable.ic_stat_mettle)
            .setContentTitle(if (kind == "fall") "Did you fall? Are you OK?" else "Are you OK?")
            .setContentText("Help will be alerted in 30 seconds unless you tap I'm OK.")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true).setContentIntent(open).setAutoCancel(false).setOngoing(true).build()
        c.getSystemService(NotificationManager::class.java).notify(NOTIF_COUNTDOWN, n)
        runCatching { c.startActivity(Intent(c, AreYouOkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    const val NOTIF_COUNTDOWN = 3001
}

@Serializable
private data class AlertRow(val id: String, @SerialName("user_id") val userId: String, val kind: String,
                            val status: String = "sent", val location: String?, val detail: JsonObject)

/** Sends alerts: SMS to emergency contacts straight away (works offline), then to the server for the admin. */
object Alerts {
    const val CHANNEL = "alerts"

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Safety alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Falls, SOS, no-movement and arrive/leave alerts"
            })
        }
    }

    suspend fun send(context: Context, kind: String, detail: Map<String, String> = emptyMap(), sms: Boolean = kind in setOf("fall", "sos", "no_movement")) {
        val db = (context.applicationContext as MettleApplication).database
        val last = db.track().latest()
        val where = last?.let { "https://maps.google.com/?q=${it.lat},${it.lon}" }
        if (sms) {
            val text = when (kind) {
                "fall" -> "Mettle: a possible FALL was detected and not cancelled."
                "sos" -> "Mettle SOS: help requested."
                else -> "Mettle: no movement detected for a long time."
            } + (where?.let { " Last location: $it" } ?: " Location unknown.")
            sendSms(context, text)
        }
        val q = SafetyPrefs.queue(context)
        q.put(JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind).put("lat", last?.lat ?: JSONObject.NULL)
            .put("lon", last?.lon ?: JSONObject.NULL).put("detail", JSONObject(detail as Map<*, *>)).put("t", System.currentTimeMillis()))
        SafetyPrefs.setQueue(context, q)
        AlertUploadWorker.enqueue(context)
        channel(context)
        context.getSystemService(NotificationManager::class.java).notify(3002,
            NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_mettle)
                .setContentTitle(if (sms) "Alert sent" else "Mettle")
                .setContentText(if (sms) "Your emergency contacts and admin are being told." else (detail["message"] ?: kind))
                .setAutoCancel(true).build())
    }

    @SuppressLint("MissingPermission")
    private fun sendSms(context: Context, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        val sm = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        SafetyPrefs.contacts(context).forEach { c -> runCatching { sm.sendMultipartTextMessage(c.phone, null, sm.divideMessage(text), null, null) } }
    }
}

@Serializable
private data class ContactRow(val id: String, @SerialName("user_id") val userId: String, val name: String, val phone: String)

@Serializable
private data class SafetySettingsRow(
    @SerialName("fall_detection") val fall: Boolean, @SerialName("no_movement_alert") val noMove: Boolean,
    @SerialName("no_movement_hours") val hours: Int, @SerialName("no_movement_from") val from: String, @SerialName("no_movement_to") val to: String,
)

/** Uploads queued alerts, emergency contacts and safety settings as soon as there is internet. */
class AlertUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val auth = Backend.client.auth
        auth.awaitInitialization()
        val userId = auth.currentUserOrNull()?.id ?: return Result.success()
        return runCatching {
            val q = SafetyPrefs.queue(applicationContext)
            if (q.length() > 0) {
                Backend.client.from("alerts").upsert((0 until q.length()).map { i ->
                    val o = q.getJSONObject(i)
                    val det = o.getJSONObject("detail")
                    AlertRow(o.getString("id"), userId, o.getString("kind"),
                        location = if (o.isNull("lat")) null else "SRID=4326;POINT(${o.getDouble("lon")} ${o.getDouble("lat")})",
                        detail = buildJsonObject { det.keys().forEach { k -> put(k, det.getString(k)) } })
                }) { onConflict = "id" }
                SafetyPrefs.setQueue(applicationContext, JSONArray())
            }
            if (SafetyPrefs.isDirty(applicationContext, "contacts_dirty")) {
                Backend.client.from("emergency_contacts").delete { filter { eq("user_id", userId) } }
                val list = SafetyPrefs.contacts(applicationContext)
                if (list.isNotEmpty()) Backend.client.from("emergency_contacts").insert(list.map { ContactRow(UUID.randomUUID().toString(), userId, it.name, it.phone) })
                SafetyPrefs.clean(applicationContext, "contacts_dirty")
            }
            if (SafetyPrefs.isDirty(applicationContext, "settings_dirty")) {
                val c = applicationContext
                Backend.client.from("profiles").update(SafetySettingsRow(SafetyPrefs.fallDetection(c), SafetyPrefs.noMovement(c),
                    SafetyPrefs.noMovementHours(c), SafetyPrefs.noMovementFrom(c).toString(), SafetyPrefs.noMovementTo(c).toString())) {
                    filter { eq("id", userId) }
                }
                SafetyPrefs.clean(applicationContext, "settings_dirty")
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("alerts", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<AlertUploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
    }
}
