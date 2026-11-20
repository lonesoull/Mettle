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

private fun stateLabel(s: DayState) = when (s) {
    DayState.RUNNING -> "Running"; DayState.WALKING -> "Walking"; DayState.CYCLING -> "Cycling"
    DayState.VEHICLE -> "Vehicle"; DayState.STILL -> "Still"; DayState.UNKNOWN -> "Unknown"
    DayState.SITTING -> "Sitting"; DayState.STANDING -> "Standing"
}

@Composable
fun TodayCard(db: MettleDatabase, trackingOn: Boolean, onSetup: () -> Unit, onReplay: () -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone).toString() }
    val minutes by db.day().minutes(today).collectAsState(emptyList())
    val body = remember { ProfileCache.body(context) }
    val goal = remember { ProfileCache.stepGoal(context) }
    val s = remember(minutes) { DayMath.summary(minutes, body) }
    val segments = remember(minutes) { DayMath.segments(minutes) }
    val ring by animateFloatAsState((s.steps.toFloat() / goal).coerceIn(0f, 1f), tween(900), label = "steps")

    Section {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Today", style = MaterialTheme.typography.titleLarge)
                Text("Replay day ›", style = MaterialTheme.typography.titleSmall, color = MettleColors.Orange,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onReplay).padding(vertical = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (trackingOn) MettleColors.Good else MettleColors.Warn))
                Text(if (trackingOn) "Tracking" else "Tracking off", style = MettleType.Label, color = MettleColors.Sub)
            }
        }
        if (!trackingOn) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MettleColors.OrangeWash)
                    .clickable(role = Role.Button, onClick = onSetup).padding(14.dp),
            ) {
                Column {
                    Text("Set up all-day tracking", style = MaterialTheme.typography.titleMedium, color = MettleColors.OrangeDeep)
                    Text("Steps, activity and your day's timeline. Takes a minute.", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 10.dp.toPx(); val r = size.minDimension / 2 - w / 2
                    val tl = Offset(center.x - r, center.y - r)
                    drawArc(MettleColors.Band, -90f, 360f, false, tl, Size(r * 2, r * 2), style = Stroke(w))
                    drawArc(MettleColors.Orange, -90f, 360f * ring, false, tl, Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format(Locale.US, "%,d", s.steps), style = MettleType.StatMedium)
                    Text(String.format(Locale.US, "of %,d", goal), style = MettleType.Label, color = MettleColors.Sub)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Mini("Distance", Fmt.km2(s.distanceM.toLong()) + " km")
                Mini("Active", "${s.activeMin} min")
                Mini("Calories", "${s.activeCalories} kcal")
                if (s.floorsUp + s.floorsDown + s.liftTrips > 0) Mini("Floors", "▲${s.floorsUp} ▼${s.floorsDown}" + if (s.liftTrips > 0) " · lift ${s.liftTrips}" else "")
            }
        }
        // 24-hour timeline
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp)).background(MettleColors.Band)) {
                val perMin = size.width / 1440f
                segments.forEach { seg ->
                    drawRoundRect(stateColor(seg.state), Offset(seg.startMinute * perMin, 0f),
                        Size(((seg.endMinute - seg.startMinute) * perMin).coerceAtLeast(1.5f), size.height), CornerRadius(2f))
                }
                val now = LocalTime.now(zone).let { it.hour * 60 + it.minute } * perMin
                drawLine(MettleColors.Ink, Offset(now, 0f), Offset(now, size.height), strokeWidth = 2f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00", "06", "12", "18", "24").forEach { Text(it, style = MettleType.Label, color = MettleColors.Muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(DayState.SITTING, DayState.STANDING, DayState.WALKING, DayState.RUNNING, DayState.VEHICLE).forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor(st)))
                        Text(stateLabel(st), style = MettleType.Label, color = MettleColors.Sub)
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
