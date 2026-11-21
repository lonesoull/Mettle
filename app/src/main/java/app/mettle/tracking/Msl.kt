package app.mettle.tracking

import android.content.Context
import android.location.Location
import android.os.Build

/**
 * GPS reports height above the WGS84 ellipsoid, which can be ~90 m off sea level (e.g. −77 m in Chennai).
 * Android 14+ ships a geoid model that converts it to true height above sea level.
 * The difference barely changes within ~10 km, so it is worked out once per area and cached.
 */
object Msl {
    @Volatile private var offset: Double? = null
    @Volatile private var key: String? = null

    private fun keyOf(l: Location) = "${(l.latitude * 10).toInt()}:${(l.longitude * 10).toInt()}"

    /** True when [l] can be corrected right now without disk work. */
    fun ready(l: Location) = Build.VERSION.SDK_INT < 34 || key == keyOf(l)

    /** Works out the sea-level correction for this area. Blocking (reads the geoid model): call off the main thread. */
    fun prepare(c: Context, l: Location) {
        if (Build.VERSION.SDK_INT < 34 || !l.hasAltitude() || key == keyOf(l)) return
        runCatching {
            val copy = Location(l)
            android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(c, copy)
            if (copy.hasMslAltitude()) { offset = copy.mslAltitudeMeters - copy.altitude; key = keyOf(l) }
        }
    }

    /** Height above sea level if the correction is ready, otherwise null (Android 14+) or the raw GPS height (older phones). */
    fun corrected(l: Location): Double? {
        if (!l.hasAltitude()) return null
        if (Build.VERSION.SDK_INT < 34) return l.altitude
        return if (key == keyOf(l)) offset?.let { l.altitude + it } else null
    }

    /** Blocking convenience for background code. */
    fun altitude(c: Context, l: Location): Double? { prepare(c, l); return corrected(l) ?: l.altitude.takeIf { Build.VERSION.SDK_INT < 34 && l.hasAltitude() } }
}
