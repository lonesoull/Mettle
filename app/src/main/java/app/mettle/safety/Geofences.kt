package app.mettle.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.mettle.data.remote.Backend
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeofenceRule(val id: String, @SerialName("owner_id") val ownerId: String, @SerialName("subject_id") val subjectId: String,
                        @SerialName("place_id") val placeId: String, @SerialName("on_arrive") val onArrive: Boolean = true,
                        @SerialName("on_leave") val onLeave: Boolean = false, val active: Boolean = true)

@Serializable
data class PlaceView(val id: String, @SerialName("user_id") val userId: String, val name: String, val kind: String = "other",
                     @SerialName("radius_m") val radiusM: Int = 100, val lat: Double? = null, val lon: Double? = null)

/** Arrive/leave alerts: registers low-power Android geofences for every rule that watches this person. */
object Geofences {
    @SuppressLint("MissingPermission")
    suspend fun refresh(context: Context, userId: String) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val client = LocationServices.getGeofencingClient(context)
        val pi = pendingIntent(context)
        if (!ok) return
        val rules = Backend.client.from("geofence_rules").select { filter { eq("subject_id", userId); eq("active", true) } }.decodeList<GeofenceRule>()
        if (rules.isEmpty()) { runCatching { client.removeGeofences(pi) }; return }
        val places = Backend.client.from("places_live").select { filter { isIn("id", rules.map { it.placeId }.distinct()) } }.decodeList<PlaceView>()
        val fences = rules.mapNotNull { r ->
            val p = places.firstOrNull { it.id == r.placeId && it.lat != null && it.lon != null } ?: return@mapNotNull null
            var types = 0
            if (r.onArrive) types = types or Geofence.GEOFENCE_TRANSITION_ENTER
            if (r.onLeave) types = types or Geofence.GEOFENCE_TRANSITION_EXIT
            if (types == 0) return@mapNotNull null
            context.getSharedPreferences("geofences", Context.MODE_PRIVATE).edit().putString(r.id, p.name).apply()
            Geofence.Builder().setRequestId(r.id).setCircularRegion(p.lat!!, p.lon!!, p.radiusM.coerceAtLeast(100).toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(types).setNotificationResponsiveness(60_000).build()
        }.take(90)
        runCatching { client.removeGeofences(pi) }
        if (fences.isNotEmpty()) runCatching {
            client.addGeofences(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build(), pi)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 77, Intent(context, GeofenceReceiver::class.java), flags)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val kind = when (event.geofenceTransition) { Geofence.GEOFENCE_TRANSITION_ENTER -> "arrive"; Geofence.GEOFENCE_TRANSITION_EXIT -> "leave"; else -> return }
        val names = context.getSharedPreferences("geofences", Context.MODE_PRIVATE)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                event.triggeringGeofences?.forEach { g ->
                    val place = names.getString(g.requestId, "a place") ?: "a place"
                    Alerts.send(context, kind, mapOf("place" to place, "rule" to g.requestId,
                        "message" to (if (kind == "arrive") "Arrived at $place" else "Left $place")), sms = false)
                }
            } finally { pending.finish() }
        }
    }
}
