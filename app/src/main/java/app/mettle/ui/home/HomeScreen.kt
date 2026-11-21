package app.mettle.ui.home

import androidx.compose.foundation.background
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
    onOpen: (String) -> Unit, onResumeSave: () -> Unit, onLiveWorkout: () -> Unit,
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

    Column(Modifier.fillMaxSize().background(MettleColors.Band)) {
        TopBar("METTLE", wordmark = true) {
            IconButton(onClick = onSafety, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(MettleIcons.Shield, contentDescription = "Safety and SOS", tint = SOS_RED)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (live.recording) item { Banner("Workout in progress · tap to return", onLiveWorkout) }
            else if (unsaved) item { Banner("You have an unsaved workout · tap to save it", onResumeSave) }
            item { TodayCard(db, trackingOn, onSetup, onDay) }
            item { ReadinessCard(onHealth) }
            item { SectionHeader("Your activities") }
            if (recent.isEmpty()) item {
                Section { EmptyState("No workouts yet", "Record a run, walk, hike or stair session. It will show up here with its map, splits and records.", "Record a workout", onRecord) }
            } else items(recent, key = { it.id }) { ActivityCard(it, zone) { onOpen(it.id) } }
            if (group.isNotEmpty()) {
                item { SectionHeader("Your group") }
                items(group, key = { "g" + it.id }) { GroupFeedCard(it) }
            }
            item { Spacer(Modifier.height(Space.l)) }
        }
    }
}

val SOS_RED = androidx.compose.ui.graphics.Color(0xFFDC2626)

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MettleColors.Ink,
        modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.m).semantics { heading() })
}

@Composable
private fun Banner(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(MettleColors.Orange).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MettleColors.Surface, modifier = Modifier.weight(1f))
        Icon(MettleIcons.Chevron, contentDescription = null, tint = MettleColors.Surface)
    }
}

@Composable
private fun ActivityCard(a: ActivityEntity, zone: ZoneId, onOpen: () -> Unit) {
    val whenText = remember(a.startedAt) { Instant.ofEpochMilli(a.startedAt).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")) }
    val route = remember(a.routePolyline) { a.routePolyline?.let { Polyline.decode(it) }.orEmpty() }
    Section(Modifier.clickable(role = Role.Button, onClickLabel = "Open ${a.title}", onClick = onOpen)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(whenText, style = MettleType.Label, color = MettleColors.Sub)
            Text(a.title, style = MaterialTheme.typography.headlineMedium)
        }
        Row(Modifier.fillMaxWidth()) {
            if (a.type == ActivityType.STAIRS) {
                Stat("Floors up", a.floorsUp.toString(), Modifier.weight(1f))
                Stat("Floors down", a.floorsDown.toString(), Modifier.weight(1f))
                Stat("Time", Fmt.duration(a.elapsedS.toLong()), Modifier.weight(1f))
            } else {
                Stat("Distance", Fmt.km2(a.distanceM.toLong()), Modifier.weight(1f), unit = "km")
                Stat("Pace", Fmt.pace(a.avgPaceSKm), Modifier.weight(1f), unit = "/km")
                Stat("Time", Fmt.duration(a.movingS.toLong()), Modifier.weight(1f))
            }
        }
        RouteSketch(route)
    }
}

/** Shown only when there is something to base readiness on. */
@Composable
private fun ReadinessCard(onHealth: () -> Unit) {
    val ctx = LocalContext.current
    val r by produceState<Int?>(null) { value = runCatching { app.mettle.health.HealthStore.readiness(ctx) }.getOrNull() }
    val score = r ?: return
    Section(Modifier.clickable(role = Role.Button, onClick = onHealth)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            Text("$score", style = MettleType.StatLarge, color = if (score >= 50) MettleColors.Good else MettleColors.Warn)
            Column(Modifier.weight(1f)) {
                Text("Readiness", style = MaterialTheme.typography.titleMedium)
                Text(app.mettle.ui.health.readinessWord(score), style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
            }
            Icon(MettleIcons.Chevron, contentDescription = null, tint = MettleColors.Muted)
        }
    }
}
