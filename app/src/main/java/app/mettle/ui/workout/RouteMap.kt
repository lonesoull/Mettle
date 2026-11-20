package app.mettle.ui.workout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.mettle.ui.theme.MettleColors
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * Google map (free Maps SDK) with Mettle's orange route on a white casing.
 * [follow] keeps the camera on the latest point (live recording); otherwise it fits the whole route.
 */
@Composable
fun RouteMap(points: List<Pair<Double, Double>>, follow: Boolean, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val latLngs = points.map { LatLng(it.first, it.second) }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(latLngs.lastOrNull() ?: LatLng(12.9716, 77.5946), if (latLngs.isEmpty()) 11f else 16f)
    }
    LaunchedEffect(latLngs.size, follow) {
        if (latLngs.isEmpty()) return@LaunchedEffect
        runCatching {
            if (follow || latLngs.size == 1) camera.animate(CameraUpdateFactory.newLatLngZoom(latLngs.last(), 17f), 600)
            else {
                val b = LatLngBounds.builder(); latLngs.forEach { b.include(it) }
                camera.move(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
            }
        }
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        properties = MapProperties(isBuildingEnabled = true),
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false,
            scrollGesturesEnabled = interactive, zoomGesturesEnabled = interactive, rotationGesturesEnabled = interactive, tiltGesturesEnabled = interactive),
    ) {
        if (latLngs.size >= 2) {
            Polyline(points = latLngs, color = MettleColors.Surface, width = 18f, zIndex = 1f)
            Polyline(points = latLngs, color = MettleColors.Orange, width = 10f, zIndex = 2f)
        }
        latLngs.firstOrNull()?.let { Circle(center = it, radius = 6.0, fillColor = MettleColors.Good, strokeColor = MettleColors.Surface, strokeWidth = 6f, zIndex = 3f) }
        if (follow) latLngs.lastOrNull()?.let { Circle(center = it, radius = 7.0, fillColor = MettleColors.Descent, strokeColor = MettleColors.Surface, strokeWidth = 8f, zIndex = 4f) }
    }
}
