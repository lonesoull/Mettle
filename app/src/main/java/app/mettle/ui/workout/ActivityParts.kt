package app.mettle.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.mettle.data.db.BestEffortEntity
import app.mettle.data.remote.Backend
import app.mettle.ui.Fmt
import app.mettle.ui.components.Section
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.MettleType
import app.mettle.workout.Streams
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

// ------------------------------------------------------------------ charts

private data class Series(val name: String, val unit: String, val color: Color, val values: List<Double?>, val invert: Boolean = false,
                          val format: (Double) -> String)

/**
 * Pace, elevation and heart rate stacked against distance, Strava-style: drag a finger across
 * any chart and one cursor moves through all of them, with the values at that point shown above.
 */
@Composable
fun ScrubCharts(s: Streams) {
    val total = s.dist.lastOrNull()?.takeIf { it > 0 } ?: return
    // Pace smoothed over ~100 m windows (raw per-second pace is unreadably spiky).
    val pace = remember(s) {
        val out = ArrayList<Double?>(s.dist.size); var j = 0
        for (i in s.dist.indices) {
            while (j < s.dist.lastIndex && s.dist[j] - s.dist[i] < 100) j++
            val d = s.dist[j] - s.dist[i]; val dt = (s.t[j] - s.t[i]).toDouble()
            out += if (d >= 30 && dt > 0 && s.moving[i]) (dt / (d / 1000.0)).coerceIn(120.0, 1200.0) else null
        }
        out
    }
    val series = remember(s) {
        buildList {
            if (pace.count { it != null } > 10) add(Series("Pace", "/km", MettleColors.Orange, pace, invert = true) { Fmt.pace(it) })
            val altVals = s.alt
            val lo = altVals.filterNotNull().minOrNull(); val hi = altVals.filterNotNull().maxOrNull()
            if (lo != null && hi != null && hi - lo >= 3) add(Series("Elevation", "m", MettleColors.Muted, altVals) { "${it.toInt()}" })
            s.hr?.takeIf { h -> h.count { it != null } > 10 }?.let { h -> add(Series("Heart rate", "bpm", Color(0xFFDC2626), h.map { it?.toDouble() }) { "${it.toInt()}" }) }
        }
    }
    if (series.isEmpty()) return
    var cursor by remember(s) { mutableStateOf<Float?>(null) }       // 0..1 along distance
    val idx = cursor?.let { c -> val target = c * total; s.dist.indexOfFirst { it >= target }.takeIf { it >= 0 } ?: s.dist.lastIndex }

    Section(title = "Analysis") {
        // Readout: values under the finger, or a hint.
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (idx == null) Text("Drag across a chart to see values along your route", style = MettleType.Label, color = MettleColors.Sub)
            else {
                Readout("Distance", Fmt.km2(s.dist[idx].toLong()) + " km", Modifier.weight(1f))
                Readout("Time", Fmt.duration(s.t[idx].toLong()), Modifier.weight(1f))
                series.forEach { ser -> Readout(ser.name, ser.values.getOrNull(idx)?.let { ser.format(it) + " " + ser.unit } ?: "–", Modifier.weight(1f), ser.color) }
            }
        }
        series.forEach { ser -> Chart(ser, s.dist, total, cursor) { cursor = it } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 km", style = MettleType.Label, color = MettleColors.Sub)
            Text(Fmt.km2(total.toLong()) + " km", style = MettleType.Label, color = MettleColors.Sub)
        }
    }
}

@Composable
private fun Readout(label: String, value: String, modifier: Modifier, color: Color = MettleColors.Ink) {
    Column(modifier) {
        Text(label, style = MettleType.Label, color = MettleColors.Sub)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun Chart(ser: Series, dist: List<Double>, total: Double, cursor: Float?, onCursor: (Float?) -> Unit) {
    val vals = ser.values.filterNotNull()
    val lo = vals.min(); val hi = vals.max().coerceAtLeast(lo + 1)
    val avg = vals.average()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ser.name, style = MaterialTheme.typography.titleSmall)
            Text(
                if (ser.invert) "avg ${ser.format(avg)} ${ser.unit} · best ${ser.format(lo)}"
                else "avg ${ser.format(avg)} · max ${ser.format(hi)} ${ser.unit}",
                style = MettleType.Label, color = MettleColors.Sub,
            )
        }
        Canvas(
            Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp)
                .semantics { contentDescription = "${ser.name} chart. Average ${ser.format(avg)} ${ser.unit}." }
                .pointerInput(total) {
                    detectDragGestures(
                        onDragStart = { o -> onCursor((o.x / size.width).coerceIn(0f, 1f)) },
                        onDrag = { ch, _ -> onCursor((ch.position.x / size.width).coerceIn(0f, 1f)) },
                        onDragEnd = { }, onDragCancel = { },
                    )
                }
                .pointerInput(total) { detectTapGestures { o -> onCursor((o.x / size.width).coerceIn(0f, 1f)) } },
        ) {
            fun y(v: Double): Float {
                val f = ((v - lo) / (hi - lo)).toFloat()
                return if (ser.invert) f * size.height else size.height - f * size.height   // faster pace drawn higher
            }
            val line = Path(); val fill = Path(); var started = false; var lastX = 0f
            for (i in dist.indices) {
                val v = ser.values.getOrNull(i) ?: continue
                val x = (dist[i] / total * size.width).toFloat()
                if (!started) { line.moveTo(x, y(v)); fill.moveTo(x, size.height); fill.lineTo(x, y(v)); started = true }
                else { line.lineTo(x, y(v)); fill.lineTo(x, y(v)) }
                lastX = x
            }
            fill.lineTo(lastX, size.height); fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(ser.color.copy(alpha = 0.28f), ser.color.copy(alpha = 0.02f))))
            drawPath(line, ser.color, style = Stroke(3.5f))
            // Average line
            drawLine(MettleColors.Sub.copy(alpha = 0.5f), Offset(0f, y(avg)), Offset(size.width, y(avg)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            cursor?.let { c ->
                val x = c * size.width
                drawLine(MettleColors.Ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
        }
    }
}

// ------------------------------------------------------------------ achievements

/** 🥇 fastest ever, 🥈 2nd, 🥉 3rd — for each standard distance in this workout. */
@Composable
fun Achievements(efforts: List<BestEffortEntity>) {
    if (efforts.isEmpty()) return
    val order = listOf("run_400m", "run_1k", "run_1mi", "run_5k", "run_10k", "run_half")
    Section(title = "Best efforts") {
        efforts.sortedBy { order.indexOf(it.effort).let { i -> if (i < 0) 99 else i } }.forEach { e ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Medal(e.rankAtTime)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(effortLabel(e.effort), style = MaterialTheme.typography.titleSmall)
                    when (e.rankAtTime) {
                        1 -> Text("Personal record", style = MettleType.Label, color = MettleColors.GoldText)
                        2 -> Text("2nd fastest time", style = MettleType.Label, color = MettleColors.Sub)
                        3 -> Text("3rd fastest time", style = MettleType.Label, color = MettleColors.Sub)
                    }
                }
                Text(Fmt.duration(e.timeS.toLong()), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun Medal(rank: Int?, size: Int = 32) {
    val (bg, fg, label) = when (rank) {
        1 -> Triple(Color(0xFFF5C542), Color(0xFF6B4E00), "1")
        2 -> Triple(Color(0xFFD4D7DD), Color(0xFF4A4F57), "2")
        3 -> Triple(Color(0xFFE3A774), Color(0xFF6A3A12), "3")
        else -> Triple(MettleColors.Band, MettleColors.Sub, "•")
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

// ------------------------------------------------------------------ kudos and comments

@Serializable private data class Who(@SerialName("display_name") val name: String? = null)
@Serializable private data class KudosIn(@SerialName("user_id") val userId: String, val profiles: Who? = null)
@Serializable private data class KudosOut(@SerialName("activity_id") val activityId: String, @SerialName("user_id") val userId: String)
@Serializable private data class CommentIn(
    val id: String, @SerialName("user_id") val userId: String, val body: String,
    @SerialName("created_at") val createdAt: String, val profiles: Who? = null,
)
@Serializable private data class CommentOut(@SerialName("activity_id") val activityId: String, @SerialName("user_id") val userId: String, val body: String)

/** Who gave kudos, the comment thread, and a box to add a comment. Works once the workout has synced. */
@Composable
fun ActivitySocial(activityId: String) {
    val scope = rememberCoroutineScope()
    val me = remember { runCatching { Backend.client.auth.currentUserOrNull()?.id }.getOrNull() }
    var kudos by remember { mutableStateOf<List<KudosIn>>(emptyList()) }
    var comments by remember { mutableStateOf<List<CommentIn>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching {
            kudos = Backend.client.from("kudos").select(io.github.jan.supabase.postgrest.query.Columns.raw("user_id,profiles(display_name)")) {
                filter { eq("activity_id", activityId) }
            }.decodeList()
            comments = Backend.client.from("comments").select(io.github.jan.supabase.postgrest.query.Columns.raw("id,user_id,body,created_at,profiles(display_name)")) {
                filter { eq("activity_id", activityId) }; order("created_at", Order.ASCENDING)
            }.decodeList()
            offline = false
        }.onFailure { offline = true }
        loaded = true
    }
    LaunchedEffect(activityId) { load() }

    Section(title = "Kudos and comments") {
        if (!loaded) Text("Loading…", style = MettleType.Label, color = MettleColors.Sub)
        else if (offline) Text("Can't reach the server right now.", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
        else SocialBody(activityId, me, kudos, comments, draft, { draft = it }, sending, note,
            onKudos = { mine ->
                val uid = me
                if (uid != null) scope.launch {
                    runCatching {
                        if (mine) Backend.client.from("kudos").delete { filter { eq("activity_id", activityId); eq("user_id", uid) } }
                        else Backend.client.from("kudos").insert(KudosOut(activityId, uid))
                    }.onFailure { note = "Kudos open once this workout has synced." }
                    load()
                }
            },
            onSend = {
                val uid = me
                if (uid != null) { sending = true; scope.launch {
                    runCatching { Backend.client.from("comments").insert(CommentOut(activityId, uid, draft.trim())) }
                        .onSuccess { draft = ""; note = null }
                        .onFailure { note = "Comments open once this workout has synced. Try again in a minute." }
                    load(); sending = false
                } }
            })
    }
}

@Composable
private fun SocialBody(
    activityId: String, me: String?, kudos: List<KudosIn>, comments: List<CommentIn>, draft: String, onDraft: (String) -> Unit,
    sending: Boolean, note: String?, onKudos: (Boolean) -> Unit, onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val mine = me != null && kudos.any { it.userId == me }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onKudos(mine) }) { Icon(MettleIcons.Heart, contentDescription = if (mine) "Remove your kudos" else "Give kudos", tint = if (mine) MettleColors.Orange else MettleColors.Ink2) }
            Text(
                when {
                    kudos.isEmpty() -> "No kudos yet"
                    else -> kudos.mapNotNull { it.profiles?.name }.let { names ->
                        if (names.size <= 3) names.joinToString(", ") + " gave kudos" else "${names.take(2).joinToString(", ")} and ${names.size - 2} others gave kudos"
                    }
                },
                style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2,
            )
        }
        comments.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(MettleColors.OrangeWash), contentAlignment = Alignment.Center) {
                    Text((c.profiles?.name ?: "?").take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = MettleColors.Orange)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.profiles?.name ?: "Member", style = MaterialTheme.typography.titleSmall)
                        Text(ago(c.createdAt), style = MettleType.Label, color = MettleColors.Sub)
                    }
                    Text(c.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (comments.isEmpty()) Text("No comments yet.", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { if (it.length <= 1000) onDraft(it) }, modifier = Modifier.weight(1f),
                placeholder = { Text("Add a comment") }, maxLines = 4, shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MettleColors.Orange, cursorColor = MettleColors.Orange),
            )
            IconButton(enabled = draft.isNotBlank() && !sending && me != null, onClick = onSend) { Text("Post", color = if (draft.isNotBlank()) MettleColors.Orange else MettleColors.Muted, style = MaterialTheme.typography.labelLarge) }
        }
        note?.let { Text(it, style = MettleType.Label, color = MettleColors.WarnText) }
    }
}

private fun ago(iso: String): String = runCatching {
    val d = Duration.between(Instant.parse(iso), Instant.now())
    when { d.toMinutes() < 1 -> "now"; d.toHours() < 1 -> "${d.toMinutes()} min"; d.toDays() < 1 -> "${d.toHours()} h"; else -> "${d.toDays()} d" }
}.getOrDefault("")

/** Full-screen interactive map of a workout (pinch, pan, rotate). */
@Composable
fun ActivityMapScreen(db: app.mettle.data.db.MettleDatabase, activityId: String, onBack: () -> Unit) {
    val a by db.activities().byId(activityId).collectAsState(null)
    val route = remember(a?.routePolyline) { a?.routePolyline?.let { app.mettle.workout.Polyline.decode(it) }.orEmpty() }
    Box(Modifier.fillMaxSize()) {
        RouteMap(route, follow = false, interactive = true, modifier = Modifier.fillMaxSize())
        androidx.compose.material3.Surface(onClick = onBack, shape = CircleShape, color = MettleColors.Surface, shadowElevation = 4.dp,
            modifier = Modifier.statusBarsPadding().padding(12.dp).size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(MettleIcons.Back, contentDescription = "Back") }
        }
    }
}
