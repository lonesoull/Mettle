package app.mettle.ui.workout

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Remembers where the phone last was, so maps open on you instead of a fixed city. */
object LastFix {
    private const val PREFS = "last_fix"

    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun saved(c: Context): LatLng? {
        val p = c.getSharedPreferences(PREFS, 0)
        if (!p.contains("lat")) return null
        return LatLng(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble())
    }

    fun save(c: Context, lat: Double, lon: Double) {
        c.getSharedPreferences(PREFS, 0).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
    }

    /** The phone's cached location right away (no GPS wait), or null. */
    @SuppressLint("MissingPermission")
    suspend fun quick(c: Context): LatLng? {
        if (!hasPermission(c)) return null
        return runCatching {
            suspendCancellableCoroutine { cont ->
                LocationServices.getFusedLocationProviderClient(c).lastLocation
                    .addOnSuccessListener { l -> cont.resume(l?.let { LatLng(it.latitude, it.longitude) }) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }.getOrNull()?.also { save(c, it.latitude, it.longitude) }
    }

    /** A fresh, accurate fix (warms up GPS before a workout). Returns location and accuracy in metres. */
    @SuppressLint("MissingPermission")
    suspend fun fresh(c: Context): Pair<LatLng, Float>? {
        if (!hasPermission(c)) return null
        val cts = CancellationTokenSource()
        return runCatching {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { cts.cancel() }
                LocationServices.getFusedLocationProviderClient(c).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { l -> cont.resume(l?.let { LatLng(it.latitude, it.longitude) to it.accuracy }) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }.getOrNull()?.also { save(c, it.first.latitude, it.first.longitude) }
    }
}
