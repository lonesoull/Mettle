package app.mettle.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.mettle.data.db.ActivityEntity
import app.mettle.data.db.ActivityType
import app.mettle.data.db.MettleDatabase
import app.mettle.data.db.PeriodTotals
import app.mettle.ui.Fmt
import app.mettle.ui.components.Section
import app.mettle.ui.components.Stat
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private const val DEFAULT_KM_GOAL = 25_000L
private const val DEFAULT_FLOORS_GOAL = 200L

@Composable
fun HomeScreen(db: MettleDatabase, onRecord: () -> Unit, onProgress: () -> Unit, onSetup: () -> Unit, onReplay: () -> Unit, onSafety: () -> Unit, onHealth: () -> Unit, onOpen: (String) -> Unit, onResumeSave: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var trackingOn by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        trackingOn = app.mettle.tracking.TrackingService.isEnabled(ctx) && app.mettle.ui.tracking.setupStatus(ctx).essentials
        onPauseOrDispose { }
    }
    val zone = remember { ZoneId.systemDefault() }
    val weekStart = remember { LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val from = remember { weekStart.atStartOfDay(zone).toInstant().toEpochMilli() }
    val to = remember { weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() }
    val totals by db.activities().totals(from, to).collectAsState(PeriodTotals(0, 0, 0.0, 0, 0))
    val week by db.activities().between(from, to).collectAsState(emptyList())
    val recent by db.activities().recent().collectAsState(emptyList())

    Column(Modifier.fillMaxSize().background(MettleColors.Band)) {
        TopBar("METTLE", wordmark = true) {
            androidx.compose.material3.TextButton(onClick = onSafety) { Text("Safety · SOS", color = androidx.compose.ui.graphics.Color(0xFFDC2626)) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            item { Spacer(Modifier.height(0.dp)) }
            val live = app.mettle.workout.WorkoutService.live.value
            if (live.recording) item { Banner("Workout in progress — tap to return", onRecord) }
            else if (app.mettle.workout.WorkoutService.unfinished(ctx) != null) item { Banner("You have an unsaved workout — tap to save it", onResumeSave) }
            item { ReadinessCard(onHealth) }
            item { TodayCard(db, trackingOn, onSetup, onReplay) }
            item { WeekCard(totals, week, weekStart, zone, onProgress) }
            if (recent.isEmpty()) {
                item { EmptyFeed(onRecord) }
            } else {
                items(recent, key = { it.id }) { ActivityCard(it, zone, onOpen) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun WeekCard(totals: PeriodTotals, week: List<ActivityEntity>, weekStart: LocalDate, zone: ZoneId, onProgress: () -> Unit) {
    val distFrac = (totals.distanceM.toFloat() / DEFAULT_KM_GOAL).coerceIn(0f, 1f)
    val floorFrac = (totals.floorsUp.toFloat() / DEFAULT_FLOORS_GOAL).coerceIn(0f, 1f)
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }

    Section {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Your week", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text("See progress", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = MettleColors.Orange,
                modifier = Modifier.clickable(onClick = onProgress).padding(vertical = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val outer = 9.dp.toPx()
                    val r1 = size.minDimension / 2 - outer / 2
                    val r2 = r1 - 13.dp.toPx()
                    fun ring(r: Float, frac: Float, color: Color) {
                        val tl = Offset(center.x - r, center.y - r)
                        drawArc(MettleColors.Band, -90f, 360f, false, tl, Size(r * 2, r * 2), style = Stroke(outer))
                        drawArc(color, -90f, 360f * frac * anim.value, false, tl, Size(r * 2, r * 2), style = Stroke(outer, cap = StrokeCap.Round))
                    }
                    ring(r1, distFrac, MettleColors.Orange)
                    ring(r2, floorFrac, MettleColors.Ink)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.hoursMinutes(totals.movingS), style = MettleType.StatMedium)
                    Text("hours", style = MettleType.Label, color = MettleColors.Sub)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GoalLine("Distance goal", Fmt.km(totals.distanceM), "/ ${DEFAULT_KM_GOAL / 1000} km", MettleColors.Orange)
                GoalLine("Floors goal", totals.floorsUp.toString(), "/ $DEFAULT_FLOORS_GOAL", MettleColors.Ink)
            }
        }
        DayBars(week, weekStart, zone, anim.value)
    }
}

@Composable
private fun GoalLine(label: String, value: String, of: String, dot: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(label, style = MettleType.Label, color = MettleColors.Sub)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MettleType.StatMedium)
            Text(" $of", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = MettleColors.Sub, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun DayBars(week: List<ActivityEntity>, weekStart: LocalDate, zone: ZoneId, grow: Float) {
    val today = LocalDate.now(zone)
    val byDay = (0..6).map { d ->
        val day = weekStart.plusDays(d.toLong())
        val acts = week.filter { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == day }
        Triple(day, acts.sumOf { it.distanceM }, acts.sumOf { it.floorsUp })
    }
    val maxKm = byDay.maxOf { it.second }.coerceAtLeast(1)
    val maxFl = byDay.maxOf { it.third }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        byDay.forEach { (day, km, fl) ->
            val (h, color) = when {
                km > 0 -> (km.toFloat() / maxKm) to MettleColors.Orange
                fl > 0 -> (fl.toFloat() / maxFl) to MettleColors.Ink
                else -> 0f to MettleColors.Line
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.width(22.dp).height((4 + 46 * h * grow).dp).clip(RoundedCornerShape(5.dp)).background(color))
                Spacer(Modifier.height(5.dp))
                Text(day.dayOfWeek.name.take(1), style = MettleType.Label, color = if (day == today) MettleColors.Ink else MettleColors.Muted)
            }
        }
    }
}

@Composable
private fun EmptyFeed(onRecord: () -> Unit) {
    Section {
        Text("No workouts yet", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Text("Record your first run, walk, hike or stair session and it will show up here with maps, splits and records.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)
        Button(onClick = onRecord, colors = ButtonDefaults.buttonColors(containerColor = MettleColors.Orange),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
            Text("Start a workout", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ActivityCard(a: ActivityEntity, zone: ZoneId, onOpen: (String) -> Unit) {
    val whenText = remember(a.startedAt) {
        Instant.ofEpochMilli(a.startedAt).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a"))
    }
    Section(Modifier.clickable { onOpen(a.id) }) {
        Text(whenText, style = MettleType.Label, color = MettleColors.Sub)
        Text(a.title, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth()) {
            if (a.type == ActivityType.STAIRS) {
                Stat("Floors up", a.floorsUp.toString(), Modifier.weight(1f))
                Stat("Floors down", a.floorsDown.toString(), Modifier.weight(1f))
            } else {
                Stat("Distance", Fmt.km2(a.distanceM.toLong()), Modifier.weight(1f), unit = "km")
                Stat("Pace", Fmt.pace(a.avgPaceSKm), Modifier.weight(1f), unit = "/km")
            }
            Stat("Time", Fmt.duration(a.movingS.toLong()), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(MettleColors.Orange).clickable(onClick = onClick).padding(16.dp)) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = MettleColors.Surface)
    }
}

/** Shown only when there is something to base readiness on. */
@Composable
private fun ReadinessCard(onHealth: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val r by androidx.compose.runtime.produceState<Int?>(null) { value = runCatching { app.mettle.health.HealthStore.readiness(ctx) }.getOrNull() }
    val score = r ?: return
    Section(Modifier.clickable(onClick = onHealth)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("$score", style = MettleType.StatLarge, color = if (score >= 50) MettleColors.Good else MettleColors.Warn)
            Column {
                Text("Readiness", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(app.mettle.ui.health.readinessWord(score), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
            }
        }
    }
}
