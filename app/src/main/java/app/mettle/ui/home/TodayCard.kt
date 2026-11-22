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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(108.dp).semantics(mergeDescendants = true) { contentDescription = "${s.steps} of $goal steps" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 9.dp.toPx(); val r = size.minDimension / 2 - w / 2
                    val tl = Offset(center.x - r, center.y - r)
                    drawArc(app.mettle.ui.components.Ui.Page, -90f, 360f, false, tl, Size(r * 2, r * 2), style = Stroke(w))
                    drawArc(MettleColors.Orange, -90f, 360f * ring, false, tl, Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format(Locale.US, "%,d", s.steps), style = app.mettle.ui.components.Ui.Value)
                    Text("steps", style = app.mettle.ui.components.Ui.Caption, color = MettleColors.Sub)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                app.mettle.ui.components.IconMetric(app.mettle.ui.theme.MettleIcons.Route, "Distance", Fmt.km2(s.distanceM.toLong()) + " km")
                app.mettle.ui.components.IconMetric(app.mettle.ui.theme.MettleIcons.Clock, "Active", "${s.activeMin} min")
                if (s.floorsUp + s.floorsDown > 0) app.mettle.ui.components.IconMetric(app.mettle.ui.theme.MettleIcons.Stairs, "Floors", "${s.floorsUp} up · ${s.floorsDown} down")
                else app.mettle.ui.components.IconMetric(app.mettle.ui.theme.MettleIcons.Flame, "Calories", "${s.activeCalories} kcal")
            }
        }
        Text(String.format(Locale.US, "%d%% of your %,d-step goal", (s.steps * 100L / goal.coerceAtLeast(1)).toInt(), goal),
            style = app.mettle.ui.components.Ui.Caption, color = MettleColors.Sub)
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
            val present = remember(segments) { segments.map { it.state }.toSet() }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(DayState.SITTING, DayState.WALKING, DayState.RUNNING, DayState.VEHICLE).filter { it in present }.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor(st)))
                        Text(stateLabel(st), style = app.mettle.ui.components.Ui.Caption, color = MettleColors.Ink2)
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
