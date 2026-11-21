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

    app.mettle.ui.components.Section(title = "Today", action = "Your day", onAction = onDay) {
        if (!trackingOn) {
            app.mettle.ui.components.EmptyState("All-day tracking is off",
                "Turn it on to count steps, floors and active minutes, and to build your day's timeline.", "Set up all-day tracking", onSetup)
            return@Section
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(116.dp).semantics(mergeDescendants = true) { contentDescription = "${s.steps} of $goal steps" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 10.dp.toPx(); val r = size.minDimension / 2 - w / 2
                    val tl = Offset(center.x - r, center.y - r)
                    drawArc(MettleColors.Band, -90f, 360f, false, tl, Size(r * 2, r * 2), style = Stroke(w))
                    drawArc(MettleColors.Orange, -90f, 360f * ring, false, tl, Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format(Locale.US, "%,d", s.steps), style = MettleType.StatMedium)
                    Text(String.format(Locale.US, "of %,d steps", goal), style = MettleType.Label, color = MettleColors.Sub)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Mini("Distance", Fmt.km2(s.distanceM.toLong()) + " km")
                Mini("Active", "${s.activeMin} min")
                Mini(if (s.floorsUp + s.floorsDown > 0) "Floors" else "Calories",
                    if (s.floorsUp + s.floorsDown > 0) "▲ ${s.floorsUp}  ▼ ${s.floorsDown}" else "${s.activeCalories} kcal")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MettleColors.Band)
                .semantics { contentDescription = "Timeline of your day" }) {
                val perMin = size.width / 1440f
                segments.forEach { seg ->
                    drawRect(stateColor(seg.state), Offset(seg.startMinute * perMin, 0f),
                        Size(((seg.endMinute - seg.startMinute) * perMin).coerceAtLeast(1.5f), size.height))
                }
                val now = LocalTime.now(zone).let { it.hour * 60 + it.minute } * perMin
                drawLine(MettleColors.Ink, Offset(now, 0f), Offset(now, size.height), strokeWidth = 3f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("12 am", "6 am", "12 pm", "6 pm", "12 am").forEach { Text(it, style = MettleType.Label, color = MettleColors.Muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(DayState.SITTING, DayState.WALKING, DayState.RUNNING, DayState.VEHICLE).forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(st)))
                        Text(stateLabel(st), style = MettleType.Label, color = MettleColors.Ink2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Mini(label: String, value: String) {
    Column {
        Text(label, style = MettleType.Label, color = MettleColors.Sub)
        Text(value, style = MettleType.StatSmall)
    }
}
