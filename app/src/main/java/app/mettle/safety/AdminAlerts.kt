package app.mettle.safety

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.mettle.R
import app.mettle.data.remote.Backend
import app.mettle.sync.ProfileCache
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AlertView(
    val id: String, @SerialName("user_id") val userId: String, val kind: String, val status: String,
    val lat: Double? = null, val lon: Double? = null, val detail: JsonObject? = null, @SerialName("created_at") val createdAt: String,
    @SerialName("display_name") val name: String? = null,
)

/** On the admin's phone: checks for new alerts about every minute while tracking runs, and notifies. */
object AdminAlerts {
    suspend fun poll(context: Context) {
        if (!ProfileCache.isAdmin(context)) return
        val prefs = context.getSharedPreferences("admin_alerts", Context.MODE_PRIVATE)
        val since = prefs.getString("since", null) ?: java.time.Instant.now().minusSeconds(3600).toString()
        val fresh = Backend.client.from("alerts_live").select {
            filter { gt("created_at", since) }; order("created_at", Order.ASCENDING); limit(20)
        }.decodeList<AlertView>()
        if (fresh.isEmpty()) return
        Alerts.channel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        fresh.forEach { a ->
            val who = a.name ?: "A member"
            val title = when (a.kind) {
                "fall" -> "⚠ $who may have fallen"; "sos" -> "🆘 $who needs help"; "no_movement" -> "⚠ $who hasn't moved for hours"
                "arrive" -> "$who arrived at ${a.detail?.get("place")?.jsonPrimitive?.content ?: "a place"}"
                "leave" -> "$who left ${a.detail?.get("place")?.jsonPrimitive?.content ?: "a place"}"
                "api_usage" -> "Mettle AI usage"
                else -> "$who: ${a.kind}"
            }
            val open = if (a.lat != null && a.lon != null) PendingIntent.getActivity(context, a.id.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${a.lat},${a.lon}")), PendingIntent.FLAG_IMMUTABLE) else null
            nm.notify(a.id.hashCode(), NotificationCompat.Builder(context, Alerts.CHANNEL).setSmallIcon(R.drawable.ic_stat_mettle)
                .setContentTitle(title).setContentText(if (open != null) "Tap to see the location" else (a.detail?.get("message")?.jsonPrimitive?.content ?: ""))
                .setPriority(if (a.kind in setOf("fall", "sos", "no_movement")) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open).setAutoCancel(true).build())
        }
        prefs.edit().putString("since", fresh.last().createdAt).apply()
    }
}
