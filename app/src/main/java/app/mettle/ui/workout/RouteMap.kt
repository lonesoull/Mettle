package app.mettle.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mettle.ui.theme.MettleColors
import app.mettle.workout.WorkoutMath
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay

/** Map switches: screenshot tests turn real maps off (Google Maps can't run there). */
object MapsConfig { @Volatile var enabled = true }

/** Simplifies a route to at most ~[max] points so the map draws it without lag. */
fun thin(points: List<Pair<Double, Double>>, max: Int = 400): List<Pair<Double, Double>> {
    if (points.size <= max) return points
    var tol = 3.0
    var idx = WorkoutMath.simplify(points.map { it.first }, points.map { it.second }, tol)
    while (idx.size > max && tol < 200) { tol *= 1.8; idx = WorkoutMath.simplify(points.map { it.first }, points.map { it.second }, tol) }
    return idx.map { points[it] }
}

/**
 * Google map (free Maps SDK) with Mettle's orange route on a white casing.
 * - [follow] = live recording: camera stays on the latest point, moving only when you've moved.
 * - [lite] = static picture of the route (activity details, lists): fast, no scrolling lag.
 */
@Composable
fun RouteMap(points: List<Pair<Double, Double>>, follow: Boolean, modifier: Modifier = Modifier, interactive: Boolean = true, lite: Boolean = false,
             here: LatLng? = null) {
    if (!MapsConfig.enabled) {
        Box(modifier.background(MettleColors.Band).semantics { contentDescription = "Map" }, contentAlignment = Alignment.Center) {
            Text("Map", style = MaterialTheme.typography.titleMedium, color = MettleColors.Muted)
        }
        return
    }
    val context = LocalContext.current
    val line = remember(points.size) { thin(points).map { LatLng(it.first, it.second) } }
    // Where "you" are: the newest route point, else a fresh fix passed in, else the phone's last known spot.
    val last = points.lastOrNull()?.let { LatLng(it.first, it.second) } ?: here
    val start = remember { last ?: LastFix.saved(context) }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(start ?: LatLng(20.5937, 78.9629), if (start == null) 4f else 16f)
    }
    // Blue "you are here" dot drawn by Google (smooth, accurate); only when location is allowed.
    val showMe = follow && !lite && LastFix.hasPermission(context)
    if (follow && last == null) LaunchedEffect(Unit) {
        LastFix.quick(context)?.let { runCatching { camera.move(CameraUpdateFactory.newLatLngZoom(it, 16f)) } }
    }
    var loaded by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(10_000); if (!loaded) slow = true }

    // Live: only move the camera after ~12 m of movement. Static: fit the whole route once.
    LaunchedEffect(if (follow) last?.let { (it.latitude * 9000).toInt() to (it.longitude * 9000).toInt() } else line.size, loaded) {
        if (!loaded || line.isEmpty()) return@LaunchedEffect
        runCatching {
            if (follow && last != null) camera.animate(CameraUpdateFactory.newLatLngZoom(last, camera.position.zoom.coerceAtLeast(15f)), 500)
            else if (line.size == 1) camera.move(CameraUpdateFactory.newLatLngZoom(line[0], 15f))
            else { val b = LatLngBounds.builder(); line.forEach { b.include(it) }; camera.move(CameraUpdateFactory.newLatLngBounds(b.build(), 64)) }
        }
    }
    Box(modifier.semantics { contentDescription = if (follow) "Live map of your route" else "Map of the route" }) {
        GoogleMap(
            cameraPositionState = camera,
            googleMapOptionsFactory = { GoogleMapOptions().liteMode(lite) },
            properties = MapProperties(isBuildingEnabled = !lite, isMyLocationEnabled = showMe),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false, compassEnabled = !lite,
                scrollGesturesEnabled = interactive && !lite, zoomGesturesEnabled = interactive && !lite,
                rotationGesturesEnabled = interactive && !lite, tiltGesturesEnabled = interactive && !lite),
            onMapLoaded = { loaded = true; slow = false },
        ) {
            if (line.size >= 2) {
                Polyline(points = line, color = MettleColors.Surface, width = 16f, zIndex = 1f)
                Polyline(points = line, color = MettleColors.Orange, width = 9f, zIndex = 2f)
            }
            line.firstOrNull()?.let { Circle(center = it, radius = 6.0, fillColor = MettleColors.Good, strokeColor = MettleColors.Surface, strokeWidth = 6f, zIndex = 3f) }
        }
        if (slow) {
            Text("Map is slow to load. Check your internet connection.", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).background(MettleColors.Surface).padding(12.dp))
        }
    }
}
