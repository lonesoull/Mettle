package app.mettle.ui.workout

import androidx.compose.runtime.setValue

import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mettle.data.db.ActivityEntity
import app.mettle.data.db.LapKind
import app.mettle.data.db.MettleDatabase
import app.mettle.ui.Fmt
import app.mettle.ui.components.Section
import app.mettle.ui.components.Stat
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.MettleType
import app.mettle.workout.Polyline
import app.mettle.workout.Streams
import app.mettle.workout.WorkoutSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun effortLabel(e: String) = when (e) {
    "run_400m" -> "400 m"; "run_1k" -> "1 km"; "run_1mi" -> "1 mile"; "run_5k" -> "5 km"
    "run_10k" -> "10 km"; "run_half" -> "Half marathon"
    "stairs_up_10fl" -> "10 floors up"; "stairs_up_50fl" -> "50 floors up"; "stairs_down_15fl" -> "15 floors down"
    else -> e.replace('_', ' ')
}

@Composable
fun ActivityDetailScreen(db: MettleDatabase, activityId: String, onBack: () -> Unit, onOpenMap: () -> Unit = {}) {
    val context = LocalContext.current
    val a by db.activities().byId(activityId).collectAsState(null)
    val laps by db.laps().forActivity(activityId).collectAsState(emptyList())
    val efforts by db.bestEfforts().forActivity(activityId).collectAsState(emptyList())
    val streams by produceState<Streams?>(null, activityId) { value = withContext(Dispatchers.IO) { WorkoutSaver.readStreams(context, activityId) } }
    val act = a ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by remember { androidx.compose.runtime.mutableStateOf(false) }
    var editing by remember { androidx.compose.runtime.mutableStateOf(false) }
    var deleting by remember { androidx.compose.runtime.mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().background(MettleColors.Band), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.background(MettleColors.Surface)) {
                TopBar(act.title, onBack = onBack) {
                    if (act.type != app.mettle.data.db.ActivityType.STAIRS) androidx.compose.material3.IconButton(onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { app.mettle.transfer.Gpx.exportOne(context, act.id) }
                            if (f == null) android.widget.Toast.makeText(context, "This workout has no GPS route to share", android.widget.Toast.LENGTH_SHORT).show()
                            else {
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("application/gpx+xml").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share workout (GPX)"))
                            }
                        }
                    }) { androidx.compose.material3.Icon(app.mettle.ui.theme.MettleIcons.Share, contentDescription = "Share as GPX file") }
                    Box {
                        androidx.compose.material3.IconButton(onClick = { menu = true }) {
                            androidx.compose.material3.Icon(app.mettle.ui.theme.MettleIcons.More, contentDescription = "More options")
                        }
                        androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Edit title and notes") }, onClick = { menu = false; editing = true })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete workout", color = MettleColors.WarnText) }, onClick = { menu = false; deleting = true })
                        }
                    }
                }
                val route = remember(act.routePolyline) { act.routePolyline?.let { Polyline.decode(it) }.orEmpty() }
                if (route.size >= 5) Box(Modifier.fillMaxWidth().height(260.dp)) {
                    RouteMap(route, follow = false, interactive = false, lite = true, modifier = Modifier.fillMaxSize())
                    // Lite maps ignore touches; a clear button opens the full interactive map.
                    androidx.compose.material3.Surface(onClick = onOpenMap, shape = RoundedCornerShape(20.dp), color = MettleColors.Surface, shadowElevation = 4.dp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                        Text("Expand map", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
                else if (act.type != app.mettle.data.db.ActivityType.STAIRS) Row(Modifier.fillMaxWidth().background(MettleColors.Band).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Icon(app.mettle.ui.theme.MettleIcons.Info, contentDescription = null, tint = MettleColors.Sub)
                    Text("No route map: GPS recorded only ${route.size} point${if (route.size == 1) "" else "s"}. This usually happens indoors or when the workout is very short.",
                        style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                }
                Header(act)
            }
        }
        item {
            val insight by produceState<app.mettle.insight.Insight?>(null, act.id) { value = runCatching { app.mettle.insight.Insights.forActivity(context, act) }.getOrNull() }
            insight?.let { ins ->
                Section {
                    Text(if (ins.ai) "✦ Mettle Insight · AI" else "✦ Mettle Insight", style = MaterialTheme.typography.titleSmall, color = MettleColors.Orange)
                    Text(ins.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (streams != null && streams!!.dist.size > 10 && streams!!.dist.last() >= 300) item { ScrubCharts(streams!!) }
        val splits = laps.filter { it.kind == LapKind.AUTO_KM }
        if (splits.isNotEmpty()) item {
            Section {
                Text("Splits", style = MaterialTheme.typography.titleLarge)
                val paces = splits.map { it.movingS / (it.distanceM / 1000.0) }
                val fastest = paces.minOrNull() ?: 0.0; val slowest = paces.maxOrNull() ?: 1.0
                splits.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (s.distanceM == 1000) "${s.idx}" else String.format("%.2f", s.distanceM / 1000.0), style = MettleType.Label, modifier = Modifier.width(34.dp))
                        Text(Fmt.pace(paces[i]), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(48.dp))
                        val frac = if (slowest > fastest) 0.4f + 0.6f * ((slowest - paces[i]) / (slowest - fastest)).toFloat() else 1f
                        Box(Modifier.weight(1f).height(16.dp)) {
                            Box(Modifier.fillMaxWidth(frac).height(16.dp).clip(RoundedCornerShape(4.dp)).background(MettleColors.Orange.copy(alpha = 0.45f + 0.55f * frac)))
                        }
                        Text("${if (s.elevGainM >= 0) "+" else ""}${s.elevGainM.toInt()} m", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.width(48.dp))
                    }
                }
            }
        }
        val sets = laps.filter { it.kind == LapKind.STAIR_SET }
        if (sets.isNotEmpty()) item {
            Section {
                Text("Sets", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth()) {
                    Text("Set", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.width(40.dp))
                    Text("▲ floors · time", style = MettleType.Label, color = MettleColors.Orange, modifier = Modifier.weight(1f))
                    Text("▼ floors · time", style = MettleType.Label, color = MettleColors.Descent, modifier = Modifier.weight(1f))
                    Text("Rest", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.width(48.dp))
                }
                sets.forEach { st ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("${st.idx}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(40.dp))
                        Text("${st.floorsUp} · ${Fmt.duration((st.climbS ?: 0).toLong())}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (st.floorsDown > 0) "${st.floorsDown} · ${Fmt.duration((st.descentS ?: 0).toLong())}" else "–", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(Fmt.duration((st.restS ?: 0).toLong()), style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.width(48.dp))
                    }
                }
            }
        }
        if (efforts.isNotEmpty()) item { Achievements(efforts) }
        item { ActivitySocial(act.id) }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (editing) {
        var title by remember { androidx.compose.runtime.mutableStateOf(act.title) }
        var notes by remember { androidx.compose.runtime.mutableStateOf(act.description.orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit workout") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedTextField(title, { if (it.length <= 80) title = it }, label = { Text("Title") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(notes, { if (it.length <= 1000) notes = it }, label = { Text("How did it go?") }, minLines = 3)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(enabled = title.isNotBlank(), onClick = {
                editing = false
                scope.launch {
                    db.activities().upsert(act.copy(title = title.trim(), description = notes.trim().ifBlank { null }, updatedAt = System.currentTimeMillis()))
                    app.mettle.sync.SyncWorker.schedule(context)
                }
            }) { Text("Save", color = MettleColors.Orange) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { editing = false }) { Text("Cancel", color = MettleColors.Ink) } },
        )
    }
    if (deleting) androidx.compose.material3.AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this workout?") },
        text = { Text("It will be removed from your activities, progress and the group feed. This can't be undone.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            deleting = false
            scope.launch {
                val now = System.currentTimeMillis()
                db.activities().upsert(act.copy(deletedAt = now, updatedAt = now))
                app.mettle.sync.SyncWorker.schedule(context)
                onBack()
            }
        }) { Text("Delete", color = MettleColors.WarnText) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text("Cancel", color = MettleColors.Ink) } },
    )
}

@Composable
private fun Header(a: ActivityEntity) {
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(Instant.ofEpochMilli(a.startedAt).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE d MMM 'at' h:mm a")),
            style = MettleType.Label, color = MettleColors.Sub)
        a.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2) }
        if (a.type == app.mettle.data.db.ActivityType.STAIRS) Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text("▲ Floors up", style = MettleType.Label, color = MettleColors.Orange); Text("${a.floorsUp}", style = MettleType.StatLarge) }
            Column(Modifier.weight(1f)) { Text("▼ Floors down", style = MettleType.Label, color = MettleColors.Descent); Text("${a.floorsDown}", style = MettleType.StatLarge) }
            Column(Modifier.weight(1f)) { Text("Time", style = MettleType.Label, color = MettleColors.Sub); Text(Fmt.duration(a.elapsedS.toLong()), style = MettleType.StatLarge) }
        } else Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text("Distance", style = MettleType.Label, color = MettleColors.Sub); Text(Fmt.km2(a.distanceM.toLong()), style = MettleType.StatLarge) }
            Column(Modifier.weight(1f)) { Text("Pace /km", style = MettleType.Label, color = MettleColors.Sub); Text(Fmt.pace(a.avgPaceSKm), style = MettleType.StatLarge) }
            Column(Modifier.weight(1f)) { Text("Time", style = MettleType.Label, color = MettleColors.Sub); Text(Fmt.duration(a.movingS.toLong()), style = MettleType.StatLarge) }
        }
        // Only stats that mean something for this kind of workout, and only when measured.
        val stairs = a.type == app.mettle.data.db.ActivityType.STAIRS
        val tiles = buildList<Triple<String, String, String?>> {
            if (!stairs && a.gapSKm != null && a.elevGainM >= 10) add(Triple("GAP", Fmt.pace(a.gapSKm), "/km"))
            add(Triple("Climb", "${a.elevGainM.toInt()}", "m"))
            if (stairs) add(Triple("Moving", Fmt.duration(a.movingS.toLong()), null)) else add(Triple("Elapsed", Fmt.duration(a.elapsedS.toLong()), null))
            if (stairs && a.movingS > 0) add(Triple("Vertical", "${(a.elevGainM * 3600 / a.movingS).toInt()}", "m/h"))
            if (!stairs && a.avgCadenceSpm != null) add(Triple("Cadence", "${a.avgCadenceSpm}", "spm"))
            a.avgHr?.let { add(Triple("Avg HR", "$it", "bpm")) }
            a.calories?.let { add(Triple("Calories", "$it", null)) }
            a.rpe?.let { add(Triple("Effort", "$it/10", null)) }
        }
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value, unit) -> Stat(label, value, Modifier.weight(1f), unit = unit) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

