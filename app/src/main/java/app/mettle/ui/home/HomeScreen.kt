package app.mettle.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import app.mettle.ui.components.Avatar
import app.mettle.ui.components.Metric
import app.mettle.ui.components.PageSection
import app.mettle.ui.components.PrimaryButton
import app.mettle.ui.components.ProCard
import app.mettle.ui.components.Ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mettle.data.db.ActivityEntity
import app.mettle.data.db.ActivityType
import app.mettle.data.db.MettleDatabase
import app.mettle.tracking.TrackingService
import app.mettle.ui.Fmt
import app.mettle.ui.components.EmptyState
import app.mettle.ui.components.RouteSketch
import app.mettle.ui.components.Section
import app.mettle.ui.components.Space
import app.mettle.ui.components.Stat
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.MettleType
import app.mettle.ui.tracking.setupStatus
import app.mettle.workout.Polyline
import app.mettle.workout.WorkoutService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    db: MettleDatabase, onRecord: () -> Unit, onSetup: () -> Unit, onDay: () -> Unit, onSafety: () -> Unit, onHealth: () -> Unit,
    onOpen: (String) -> Unit, onResumeSave: () -> Unit, onLiveWorkout: () -> Unit, name: String = "",
) {
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var trackingOn by remember { mutableStateOf(false) }
    var unsaved by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        trackingOn = TrackingService.isEnabled(ctx) && setupStatus(ctx).essentials
        unsaved = WorkoutService.unfinished(ctx) != null
        onPauseOrDispose { }
    }
    val live by WorkoutService.live.collectAsState()
    val recent by db.activities().recent().collectAsState(emptyList())
    val group by produceState(emptyList<FeedItem>()) { value = loadGroupFeed() }

    LazyColumn(Modifier.fillMaxSize().background(Ui.Page).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Greeting(name, onSafety) }
        if (live.recording) item { LiveCard(live, onLiveWorkout) }
        else if (unsaved) item { UnsavedCard(onResumeSave) }
        item { TodayCard(db, trackingOn, onSetup, onDay) }
        item { ReadinessCard(onHealth) }
        item { PageSection("Your activities") }
        if (recent.isEmpty()) item {
            ProCard {
                Text("No workouts yet", style = Ui.CardTitle, color = MettleColors.Ink)
                Text("Your runs, walks, hikes and stair sessions appear here.", style = Ui.Body, color = MettleColors.Sub)
                PrimaryButton("Record a workout", onClick = onRecord)
            }
        } else items(recent, key = { it.id }) { ActivityCard(it, zone, name) { onOpen(it.id) } }
        if (group.isNotEmpty()) {
            item { PageSection("Your group") }
            items(group, key = { "g" + it.id }) { GroupFeedCard(it) }
        }
        item { Spacer(Modifier.height(Space.xl)) }
    }
}

val SOS_RED = androidx.compose.ui.graphics.Color(0xFFDC2626)

@Composable
private fun Greeting(name: String, onSafety: () -> Unit) {
    val now = remember { java.time.LocalDate.now() }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")).uppercase(), style = Ui.Caption.copy(letterSpacing = 0.6.sp), color = MettleColors.Sub)
            Text("Summary", style = Ui.PageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp), color = MettleColors.Ink,
                modifier = Modifier.semantics { heading() })
        }
        IconButton(onClick = onSafety, modifier = Modifier.size(48.dp)) {
            Icon(MettleIcons.Shield, contentDescription = "Safety and SOS", tint = MettleColors.Ink)
        }
    }
}

/** While a workout records: a dark card with the live numbers, tap to return. */
@Composable
private fun LiveCard(live: app.mettle.workout.LiveWorkout, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = Ui.Gutter).fillMaxWidth().clip(Ui.CardShape).background(MettleColors.Ink)
        .clickable(role = Role.Button, onClickLabel = "Return to workout", onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (live.paused) MettleColors.Warn else MettleColors.Orange))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (live.paused) "Workout paused" else "Recording " + live.type.name.lowercase(), style = Ui.Strong, color = MettleColors.Surface)
            Text(Fmt.duration(live.movingS.toLong()) + (if (live.type != ActivityType.STAIRS) "  ·  " + Fmt.km2(live.distanceM.toLong()) + " km" else "  ·  ${live.floorsUp} floors"),
                style = Ui.Caption, color = MettleColors.Surface.copy(alpha = 0.75f))
        }
        Icon(MettleIcons.Chevron, contentDescription = null, tint = MettleColors.Surface)
    }
}

@Composable
private fun UnsavedCard(onClick: () -> Unit) {
    ProCard(onClick = onClick, onClickLabel = "Save workout") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Unsaved workout", style = Ui.Strong, color = MettleColors.Ink)
                Text("Finish saving it to keep the route and stats.", style = Ui.Caption, color = MettleColors.Sub)
            }
            Text("Save", style = MaterialTheme.typography.labelLarge, color = MettleColors.Orange)
        }
    }
}

private fun typeIcon(t: ActivityType) = when (t) {
    ActivityType.RUN -> MettleIcons.Run; ActivityType.WALK -> MettleIcons.Walk; ActivityType.HIKE -> MettleIcons.Hike
    ActivityType.STAIRS -> MettleIcons.Stairs; else -> MettleIcons.Activity
}

private fun friendlyWhen(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val day = when (t.toLocalDate()) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM")) }
    return day + " at " + t.format(DateTimeFormatter.ofPattern("h:mm a"))
}

@Composable
private fun ActivityCard(a: ActivityEntity, zone: ZoneId, name: String, onOpen: () -> Unit) {
    val whenText = remember(a.startedAt) { friendlyWhen(a.startedAt, zone) }
    val route = remember(a.routePolyline) { a.routePolyline?.let { Polyline.decode(it) }.orEmpty() }
    ProCard(onClick = onOpen, onClickLabel = "Open ${a.title}", padding = false) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MettleColors.OrangeWash), contentAlignment = Alignment.Center) {
                    Icon(typeIcon(a.type), contentDescription = a.type.name.lowercase(), tint = MettleColors.Orange, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(a.title, style = Ui.CardTitle, color = MettleColors.Ink)
                    Text(whenText, style = Ui.Caption, color = MettleColors.Sub)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                if (a.type == ActivityType.STAIRS) {
                    Metric("Floors up", a.floorsUp.toString(), Modifier.weight(1f), small = true)
                    Metric("Floors down", a.floorsDown.toString(), Modifier.weight(1f), small = true)
                    Metric("Time", Fmt.duration(a.elapsedS.toLong()), Modifier.weight(1f), small = true)
                } else {
                    Metric("Distance", Fmt.km2(a.distanceM.toLong()), Modifier.weight(1f), unit = "km", small = true)
                    Metric("Pace", Fmt.pace(a.avgPaceSKm), Modifier.weight(1f), unit = "/km", small = true)
                    Metric("Time", Fmt.duration(a.movingS.toLong()), Modifier.weight(1f), small = true)
                }
            }
        }
        if (route.size >= 5) {
            if (app.mettle.ui.workout.MapsConfig.enabled)
                app.mettle.ui.workout.RouteMap(route, follow = false, interactive = false, lite = true, modifier = Modifier.fillMaxWidth().height(170.dp))
            else RouteSketch(route, height = 170, rounded = false)
        } else Spacer(Modifier.height(2.dp))
    }
}

/** Shown only when there is something to base readiness on. */
@Composable
private fun ReadinessCard(onHealth: () -> Unit) {
    val ctx = LocalContext.current
    val r by produceState<Int?>(null) { value = runCatching { app.mettle.health.HealthStore.readiness(ctx) }.getOrNull() }
    val score = r ?: return
    ProCard(onClick = onHealth, onClickLabel = "Open health") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("$score", style = Ui.Value.copy(fontSize = 32.sp), color = if (score >= 50) MettleColors.Good else MettleColors.Warn)
            Column(Modifier.weight(1f)) {
                Text("Readiness", style = Ui.Strong, color = MettleColors.Ink)
                Text(app.mettle.ui.health.readinessWord(score), style = Ui.Caption, color = MettleColors.Sub)
            }
            Icon(MettleIcons.Chevron, contentDescription = null, tint = MettleColors.Muted, modifier = Modifier.size(20.dp))
        }
    }
}
