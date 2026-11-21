package app.mettle.ui.workout

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
fun ActivityDetailScreen(db: MettleDatabase, activityId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val a by db.activities().byId(activityId).collectAsState(null)
    val laps by db.laps().forActivity(activityId).collectAsState(emptyList())
    val efforts by db.bestEfforts().forActivity(activityId).collectAsState(emptyList())
    val streams by produceState<Streams?>(null, activityId) { value = withContext(Dispatchers.IO) { WorkoutSaver.readStreams(context, activityId) } }
    val act = a ?: return

    LazyColumn(Modifier.fillMaxSize().background(MettleColors.Band), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.background(MettleColors.Surface)) {
                TopBar(act.title, onBack = onBack)
                val route = remember(act.routePolyline) { act.routePolyline?.let { Polyline.decode(it) }.orEmpty() }
                if (route.size >= 2) RouteMap(route, follow = false, interactive = false, lite = true, modifier = Modifier.fillMaxWidth().height(260.dp))
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
        if (streams != null && streams!!.dist.size > 10) item { Charts(streams!!) }
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
        if (efforts.isNotEmpty()) item {
            Section {
                Text("Best efforts", style = MaterialTheme.typography.titleLarge)
                efforts.sortedBy { it.timeS }.sortedBy { listOf("run_400m", "run_1k", "run_1mi", "run_5k", "run_10k", "run_half").indexOf(it.effort) }.forEach { e ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(effortLabel(e.effort), style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(Fmt.duration(e.timeS.toLong()), style = MaterialTheme.typography.titleSmall)
                            if (e.rankAtTime == 1) Text("PR ★", style = MaterialTheme.typography.titleSmall, color = MettleColors.GoldText)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
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
            if (!stairs && a.gapSKm != null) add(Triple("GAP", Fmt.pace(a.gapSKm), "/km"))
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

@Composable
private fun Charts(s: Streams) {
    // Pace smoothed over ~100 m, and the elevation profile, both against distance.
    val total = s.dist.last().coerceAtLeast(1.0)
    val pace = remember(s) {
        val out = ArrayList<Pair<Double, Double>>(); var j = 0
        for (i in s.dist.indices) {
            while (j < s.dist.size && s.dist[j] - s.dist[i] < 100) j++
            if (j >= s.dist.size) break
            val dt = (s.t[j] - s.t[i]).toDouble(); val d = s.dist[j] - s.dist[i]
            if (dt > 0 && d > 0 && s.moving[j]) out += s.dist[i] to (dt / (d / 1000.0)).coerceIn(150.0, 900.0)
        }
        out
    }
    val alt = remember(s) { s.dist.indices.mapNotNull { i -> s.alt[i]?.let { s.dist[i] to it } } }
    Section {
        Text("Pace", style = MaterialTheme.typography.titleLarge)
        if (pace.size > 2) {
            val lo = pace.minOf { it.second }; val hi = pace.maxOf { it.second }
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val path = Path()
                pace.forEachIndexed { k, (d, p) ->
                    val x = (d / total * size.width).toFloat()
                    val y = ((p - lo) / (hi - lo).coerceAtLeast(1.0) * (size.height - 8) + 4).toFloat()  // faster = higher
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, MettleColors.Orange, style = Stroke(5f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("fastest ${Fmt.pace(lo)}", style = MettleType.Label, color = MettleColors.Sub)
                Text("slowest ${Fmt.pace(hi)}", style = MettleType.Label, color = MettleColors.Sub)
            }
        }
        if (alt.size > 2) {
            Text("Elevation", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            val lo = alt.minOf { it.second }; val hi = alt.maxOf { it.second }.coerceAtLeast(lo + 10)
            Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                val path = Path().apply { moveTo(0f, size.height) }
                alt.forEach { (d, h) -> path.lineTo((d / total * size.width).toFloat(), (size.height - (h - lo) / (hi - lo) * size.height).toFloat()) }
                path.lineTo(size.width, size.height); path.close()
                drawPath(path, Brush.verticalGradient(listOf(MettleColors.Muted.copy(alpha = 0.6f), MettleColors.Band)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${lo.toInt()} m", style = MettleType.Label, color = MettleColors.Sub)
                Text("${hi.toInt()} m", style = MettleType.Label, color = MettleColors.Sub)
            }
        }
    }
}
