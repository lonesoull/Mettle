package app.mettle.ui.space

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.mettle.space.Stay
import app.mettle.space.StayMath
import app.mettle.space.KnownPlace
import app.mettle.space.TrackPt
import app.mettle.ui.Fmt
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.MettleType
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
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One position in a day, with what the person was doing. */
data class ReplayPoint(val tMs: Long, val lat: Double, val lon: Double, val altitudeM: Double?, val accuracyM: Float?, val state: String)

/**
 * Scrub or play through a day: the dot moves along the day's path while time,
 * activity and height update. [load] returns that day's points (local or from the server).
 */
@Composable
fun DayReplayScreen(title: String, places: List<KnownPlace>, db: app.mettle.data.db.MettleDatabase?, load: suspend (LocalDate) -> List<ReplayPoint>, onBack: (() -> Unit)?) {
    val zone = remember { ZoneId.systemDefault() }
    var day by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    val date = LocalDate.parse(day)
    val points by produceState<List<ReplayPoint>?>(null, day) { value = null; value = runCatching { load(date) }.getOrDefault(emptyList()) }
    val pts = points.orEmpty()
    var idx by remember(day) { mutableIntStateOf(0) }
    var playing by remember(day) { mutableStateOf(false) }
    LaunchedEffect(playing, pts.size) {
        while (playing && pts.isNotEmpty()) {
            idx = (idx + (pts.size / 300).coerceAtLeast(1)).coerceAtMost(pts.lastIndex)
            if (idx == pts.lastIndex) playing = false
            delay(50)
        }
    }
    val stays = remember(pts) { StayMath.stays(pts.map { TrackPt(it.tMs, it.lat, it.lon, it.accuracyM, it.altitudeM) }) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(12.9716, 77.5946), 11f) }
    LaunchedEffect(pts) {
        if (pts.size >= 2) runCatching {
            val b = LatLngBounds.builder(); pts.forEach { b.include(LatLng(it.lat, it.lon)) }
            camera.move(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
        } else if (pts.size == 1) camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].lat, pts[0].lon), 15f))
    }

    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        TopBar(title, onBack = onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day = date.minusDays(1).toString() }, modifier = Modifier.semantics { contentDescription = "Previous day" }) { Text("‹ Prev", color = MettleColors.Orange) }
            Text(date.format(DateTimeFormatter.ofPattern("EEE d MMM")), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(enabled = date.isBefore(LocalDate.now(zone)), onClick = { day = date.plusDays(1).toString() },
                modifier = Modifier.semantics { contentDescription = "Next day" }) { Text("Next ›", color = MettleColors.Orange) }
        }
        if (db != null) DayTotals(db, day)
        Box(Modifier.fillMaxWidth().height(280.dp)) {
            if (!app.mettle.ui.workout.MapsConfig.enabled) Box(Modifier.fillMaxSize().background(MettleColors.Band)) else
            GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(isBuildingEnabled = true),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false)) {
                if (pts.size >= 2) {
                    val line = app.mettle.ui.workout.thin(pts.map { it.lat to it.lon }, 600).map { LatLng(it.first, it.second) }
                    Polyline(points = line, color = MettleColors.Surface, width = 16f)
                    Polyline(points = line, color = MettleColors.Orange, width = 8f)
                }
                stays.forEach { s -> Circle(center = LatLng(s.lat, s.lon), radius = 40.0, fillColor = MettleColors.Ink.copy(alpha = 0.15f), strokeColor = MettleColors.Ink, strokeWidth = 2f) }
                pts.getOrNull(idx)?.let { p -> Circle(center = LatLng(p.lat, p.lon), radius = 12.0, fillColor = MettleColors.Descent, strokeColor = MettleColors.Surface, strokeWidth = 8f, zIndex = 5f) }
            }
            if (points == null) Text("Loading…", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
            else if (pts.isEmpty()) Text("No location data for this day", modifier = Modifier.align(Alignment.Center).background(MettleColors.Surface).padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
        val cur = pts.getOrNull(idx)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(cur?.let { Instant.ofEpochMilli(it.tMs).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a")) } ?: "--:--", style = MettleType.StatLarge)
                Column(Modifier.weight(1f)) {
                    Text(cur?.state?.replaceFirstChar { it.uppercase() } ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(cur?.let { p -> (p.altitudeM?.let { "${it.toInt()} m above sea" } ?: "") + (p.accuracyM?.let { " · ±${it.toInt()} m" } ?: "") } ?: "",
                        style = MettleType.Label, color = MettleColors.Sub)
                }
                IconButton(onClick = { if (idx >= pts.lastIndex) idx = 0; playing = !playing },
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(MettleColors.Orange)) {
                    Icon(if (playing) MettleIcons.Pause else MettleIcons.Play, contentDescription = if (playing) "Pause replay" else "Play replay", tint = MettleColors.Surface)
                }
            }
            if (pts.size > 1) Slider(value = idx.toFloat(), onValueChange = { idx = it.toInt(); playing = false }, valueRange = 0f..pts.lastIndex.toFloat(),
                colors = SliderDefaults.colors(thumbColor = MettleColors.Orange, activeTrackColor = MettleColors.Orange,
                    inactiveTrackColor = MettleColors.Band, activeTickColor = MettleColors.Orange, inactiveTickColor = MettleColors.Band),
                modifier = Modifier.semantics { contentDescription = "Replay position" })
        }
        Text("Places", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (stays.isEmpty() && points != null) Text("No places yet for this day. Places appear after you stay somewhere for 5 minutes or more.",
            style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub, modifier = Modifier.padding(horizontal = 16.dp))
        var showAll by remember(stays) { mutableStateOf(false) }
        val shown = if (showAll) stays else stays.sortedByDescending { it.endMs - it.startMs }.take(6).sortedBy { it.startMs }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(shown) { s -> StayRow(s, StayMath.label(s, places), zone) }
            if (stays.size > 6) item {
                androidx.compose.material3.TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp)) {
                    Text(if (showAll) "Show longest places only" else "Show all ${stays.size} places", color = MettleColors.Orange, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun StayRow(s: Stay, label: String?, zone: ZoneId) {
    val f = DateTimeFormatter.ofPattern("h:mm a")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MettleColors.Ink))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label ?: "Unnamed place", style = MaterialTheme.typography.titleSmall)
            Text("${Instant.ofEpochMilli(s.startMs).atZone(zone).format(f)} – ${Instant.ofEpochMilli(s.endMs).atZone(zone).format(f)}",
                style = MettleType.Label, color = MettleColors.Sub)
        }
        Text(Fmt.hoursMinutes((s.endMs - s.startMs) / 1000) + " h", style = MaterialTheme.typography.titleSmall)
    }
}

/** That day's totals from the phone's own records (Day tab only). */
@Composable
private fun DayTotals(db: app.mettle.data.db.MettleDatabase, day: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val minutes by db.day().minutes(day).collectAsState(emptyList())
    val s = remember(minutes) { app.mettle.tracking.DayMath.summary(minutes, app.mettle.sync.ProfileCache.body(ctx)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        app.mettle.ui.components.Stat("Steps", "%,d".format(s.steps), Modifier.weight(1f))
        app.mettle.ui.components.Stat("Active", "${s.activeMin}", Modifier.weight(1f), unit = "min")
        app.mettle.ui.components.Stat("Sitting", Fmt.hoursMinutes(s.sittingMin * 60L), Modifier.weight(1f), unit = "h")
        app.mettle.ui.components.Stat("Floors", "${s.floorsUp}", Modifier.weight(1f))
    }
}
