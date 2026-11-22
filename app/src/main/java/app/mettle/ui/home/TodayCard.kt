package app.mettle.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mettle.data.db.DayState
import app.mettle.data.db.MettleDatabase
import app.mettle.sync.ProfileCache
import app.mettle.tracking.DayMath
import app.mettle.ui.Fmt
import app.mettle.ui.components.Section
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

fun stateColor(s: DayState): Color = when (s) {
    DayState.RUNNING -> MettleColors.Orange
    DayState.WALKING -> Color(0xFFFD8A4B)
    DayState.CYCLING -> MettleColors.OrangeDeep
    DayState.VEHICLE -> MettleColors.Descent
    DayState.STILL -> Color(0xFFD4D4D8)
    DayState.SITTING -> Color(0xFFC4C4CC)
    DayState.STANDING -> Color(0xFF9CA3AF)
    DayState.UNKNOWN -> MettleColors.Line
}

fun stateLabel(s: DayState) = when (s) {
    DayState.RUNNING -> "Running"; DayState.WALKING -> "Walking"; DayState.CYCLING -> "Cycling"
    DayState.VEHICLE -> "Vehicle"; DayState.STILL -> "Still"; DayState.UNKNOWN -> "Unknown"
    DayState.SITTING -> "Sitting"; DayState.STANDING -> "Standing"
}

@Composable
fun TodayCard(db: MettleDatabase, trackingOn: Boolean, onSetup: () -> Unit, onDay: () -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone).toString() }
    val minutes by db.day().minutes(today).collectAsState(emptyList())
    val body = remember { ProfileCache.body(context) }
    val goal = remember { ProfileCache.stepGoal(context) }
    val s = remember(minutes) { DayMath.summary(minutes, body) }
    val segments = remember(minutes) { DayMath.segments(minutes) }
    val ring by animateFloatAsState((s.steps.toFloat() / goal).coerceIn(0f, 1f), tween(900), label = "steps")
    val activeRing by animateFloatAsState((s.activeMin.toFloat() / ACTIVE_GOAL).coerceIn(0f, 1f), tween(900), label = "active")

    if (!trackingOn) {
        app.mettle.ui.components.ProCard {
            Text("Track your whole day", style = app.mettle.ui.components.Ui.CardTitle, color = MettleColors.Ink)
            Text("Steps, active time, floors and places, recorded automatically.", style = app.mettle.ui.components.Ui.Body, color = MettleColors.Sub)
            app.mettle.ui.components.PrimaryButton("Turn on", onClick = onSetup)
        }
        return
    }
    app.mettle.ui.components.ProCard(onClick = onDay, onClickLabel = "Open your day") {
        app.mettle.ui.components.CardHeader("Today", opens = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(116.dp).semantics(mergeDescendants = true) {
                contentDescription = "${s.steps} of $goal steps, ${s.activeMin} of $ACTIVE_GOAL active minutes"
            }) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 13.dp.toPx(); val gap = 3.dp.toPx()
                    fun ring(radius: Float, frac: Float, color: Color) {
                        val tl = Offset(center.x - radius, center.y - radius); val sz = Size(radius * 2, radius * 2)
                        drawArc(color.copy(alpha = 0.16f), -90f, 360f, false, tl, sz, style = Stroke(w))
                        drawArc(color, -90f, 360f * frac, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                    }
                    val r1 = size.minDimension / 2 - w / 2
                    ring(r1, ring, MettleColors.Orange)
                    ring(r1 - w - gap, activeRing, MettleColors.Good)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigLine(String.format(Locale.US, "%,d", s.steps), String.format(Locale.US, "/ %,d steps", goal), MettleColors.Orange)
                BigLine("${s.activeMin}", "/ $ACTIVE_GOAL active min", MettleColors.Good)
                Text(Fmt.km2(s.distanceM.toLong()) + " km" + (if (s.floorsUp > 0) "  ·  ${s.floorsUp} floors" else ""),
                    style = app.mettle.ui.components.Ui.Strong, color = MettleColors.Ink2)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(app.mettle.ui.components.Ui.Page)
                .semantics { contentDescription = "Timeline of your day" }) {
                val perMin = size.width / 1440f
                segments.forEach { seg ->
                    if (seg.state == DayState.STILL || seg.state == DayState.UNKNOWN) return@forEach
                    drawRect(stateColor(seg.state), Offset(seg.startMinute * perMin, 0f),
                        Size(((seg.endMinute - seg.startMinute) * perMin).coerceAtLeast(2f), size.height))
                }
                val now = LocalTime.now(zone).let { it.hour * 60 + it.minute } * perMin
                drawCircle(MettleColors.Ink, 4.dp.toPx(), Offset(now, size.height / 2))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { Text(it, style = app.mettle.ui.components.Ui.Caption, color = MettleColors.Muted) }
            }
        }
    }
}

/** WHO guidance: at least 30 active minutes a day. */
private const val ACTIVE_GOAL = 30

@Composable
private fun BigLine(value: String, of: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = app.mettle.ui.components.Ui.Value.copy(fontSize = 28.sp, lineHeight = 30.sp), color = color)
        Text(" $of", style = app.mettle.ui.components.Ui.Caption, color = MettleColors.Sub, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun Mini(label: String, value: String) {
    Column {
        Text(label, style = MettleType.Label, color = MettleColors.Sub)
        Text(value, style = MettleType.StatSmall)
    }
}
