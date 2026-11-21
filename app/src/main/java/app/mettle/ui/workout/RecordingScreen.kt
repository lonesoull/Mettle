package app.mettle.ui.workout

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mettle.ui.Fmt
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.MettleType
import app.mettle.workout.LiveWorkout
import app.mettle.workout.VoicePrefs
import app.mettle.workout.WorkoutService

/**
 * Live recording, Strava-style: a map view and a big-numbers view (toggle), a status chip with
 * GPS signal, one large Pause button; when paused, Resume and Finish.
 */
@Composable
fun RecordingScreen(onFinished: (sessionId: String) -> Unit) {
    val context = LocalContext.current
    val live by WorkoutService.live.collectAsState()
    var statsView by rememberSaveable { mutableStateOf(false) }
    var voice by remember { mutableStateOf(VoicePrefs.enabled(context)) }
    var screenOn by remember { mutableStateOf(VoicePrefs.keepScreenOn(context)) }
    var confirmFinish by remember { mutableStateOf(false) }

    LaunchedEffect(live.recording, live.sessionId) {
        if (!live.recording && live.sessionId != null && live.startedAt > 0) onFinished(live.sessionId!!)
    }
    // Optional: keep the screen awake while recording (glanceable on a mount or in hand).
    DisposableEffect(screenOn) {
        val w = (context as? Activity)?.window
        if (screenOn) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        AnimatedContent(statsView, label = "view", modifier = Modifier.fillMaxWidth().weight(1f)) { stats ->
            if (stats) BigStats(live)
            else Box(Modifier.fillMaxSize()) {
                RouteMap(live.route, follow = true, modifier = Modifier.fillMaxSize())
                StatusChip(live, Modifier.statusBarsPadding().padding(12.dp))
            }
        }
        if (!statsView) MapStats(live)
        HorizontalDivider(color = MettleColors.Line)
        Controls(
            live = live, statsView = statsView, voice = voice, screenOn = screenOn,
            onToggleView = { statsView = !statsView },
            onVoice = { voice = !voice; VoicePrefs.set(context, voice) },
            onScreen = { screenOn = !screenOn; VoicePrefs.setKeepScreenOn(context, screenOn) },
            onPause = { WorkoutService.pause(context) },
            onResume = { WorkoutService.resume(context) },
            onFinish = { if (live.distanceM < 50 && live.movingS < 60) confirmFinish = true else WorkoutService.finish(context) },
        )
    }

    if (confirmFinish) AlertDialog(
        onDismissRequest = { confirmFinish = false },
        title = { Text("Very short workout") },
        text = { Text("Only ${live.distanceM} m and ${Fmt.duration(live.movingS.toLong())} were recorded. Finish anyway? You can still discard it on the next screen.") },
        confirmButton = { TextButton(onClick = { confirmFinish = false; WorkoutService.finish(context) }) { Text("Finish", color = MettleColors.Orange) } },
        dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going", color = MettleColors.Ink) } },
    )
}

private fun statusOf(live: LiveWorkout): Pair<String, Color> = when {
    live.paused -> "Paused" to MettleColors.Warn
    live.autoPaused -> "Auto-paused" to MettleColors.Warn
    live.gpsAccuracyM == null -> "Searching for GPS" to MettleColors.Sub
    else -> "Recording" to MettleColors.Orange
}

@Composable
private fun StatusChip(live: LiveWorkout, modifier: Modifier = Modifier) {
    val (label, color) = statusOf(live)
    Row(modifier.shadow(4.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(MettleColors.Surface)
        .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.titleSmall, color = color)
        GpsBars(live.gpsAccuracyM)
    }
}

/** Signal bars: 3 = ≤8 m, 2 = ≤15 m, 1 = ≤25 m, 0 = worse/none. */
@Composable
private fun GpsBars(acc: Float?) {
    val level = when { acc == null -> 0; acc <= 8 -> 3; acc <= 15 -> 2; acc <= 25 -> 1; else -> 0 }
    val word = listOf("no", "weak", "fair", "strong")[level]
    Row(Modifier.semantics { contentDescription = "GPS signal $word" + (acc?.let { ", ±${it.toInt()} metres" } ?: "") },
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("GPS", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.padding(end = 3.dp))
        (1..3).forEach { b ->
            Box(Modifier.width(4.dp).height((4 + b * 4).dp).clip(RoundedCornerShape(1.dp))
                .background(if (b <= level) (if (level == 1) MettleColors.Warn else MettleColors.Good) else MettleColors.Line))
        }
    }
}

/** Under the map: time, distance, pace in a compact panel. */
@Composable
private fun MapStats(live: LiveWorkout) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Cell("Time", Fmt.duration(live.movingS.toLong()), null, Modifier.weight(1f))
            Cell("Distance", Fmt.km2(live.distanceM.toLong()), "km", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Cell("Pace", Fmt.pace(live.currentPaceSKm), "/km", Modifier.weight(1f), MettleColors.Orange)
            Cell("Avg pace", Fmt.pace(live.avgPaceSKm), "/km", Modifier.weight(1f))
        }
        val extra = listOfNotNull(
            live.heartRate?.let { "♥ $it bpm" },
            "Climb ${live.elevGainM.toInt()} m",
            "Elapsed ${Fmt.duration(live.elapsedS.toLong())}",
        ).joinToString("  ·  ")
        Text(extra, style = MettleType.Label, color = MettleColors.Sub)
    }
}

@Composable
private fun Cell(label: String, value: String, unit: String?, modifier: Modifier, color: Color = MettleColors.Ink) {
    Column(modifier) {
        Text(label, style = MettleType.Label, color = MettleColors.Sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MettleType.StatLarge, color = color)
            unit?.let { Text(" $it", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

/** Full-screen numbers, readable at arm's length mid-run. */
@Composable
private fun BigStats(live: LiveWorkout) {
    val huge = MettleType.Hero.copy(fontSize = 76.sp, lineHeight = 78.sp)
    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { StatusChip(live) }
        Spacer(Modifier.weight(1f))
        BigBlock("Time", Fmt.duration(live.movingS.toLong()), null, huge)
        HorizontalDivider(color = MettleColors.Line, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
        BigBlock("Pace", Fmt.pace(live.currentPaceSKm), "/km", huge, MettleColors.Orange)
        HorizontalDivider(color = MettleColors.Line, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
        BigBlock("Distance", Fmt.km2(live.distanceM.toLong()), "km", huge)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Cell("Avg pace", Fmt.pace(live.avgPaceSKm), "/km", Modifier.weight(1f))
            Cell("Climb", "${live.elevGainM.toInt()}", "m", Modifier.weight(1f))
            if (live.heartRate != null) Cell("Heart rate", "${live.heartRate}", "bpm", Modifier.weight(1f), MettleColors.Orange)
            else Cell("Elapsed", Fmt.duration(live.elapsedS.toLong()), null, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BigBlock(label: String, value: String, unit: String?, style: TextStyle, color: Color = MettleColors.Ink) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MettleType.Overline, color = MettleColors.Sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = style, color = color)
            unit?.let { Text(" $it", style = MaterialTheme.typography.titleMedium, color = MettleColors.Sub, modifier = Modifier.padding(bottom = 12.dp)) }
        }
    }
}

@Composable
private fun Controls(
    live: LiveWorkout, statsView: Boolean, voice: Boolean, screenOn: Boolean,
    onToggleView: () -> Unit, onVoice: () -> Unit, onScreen: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onFinish: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (live.paused) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundAction("Resume", MettleIcons.Play, MettleColors.Surface, MettleColors.Ink, Modifier.weight(1f), outlined = true, onClick = onResume)
                RoundAction("Finish", MettleIcons.Stop, MettleColors.Orange, MettleColors.Surface, Modifier.weight(1f), onClick = onFinish)
            }
        } else {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallToggle(if (statsView) MettleIcons.Map else MettleIcons.Stats, if (statsView) "Show map" else "Show big numbers", onToggleView)
                SmallToggle(if (voice) MettleIcons.Volume else MettleIcons.VolumeOff, if (voice) "Voice coach on" else "Voice coach off", onVoice)
            }
            Box(Modifier.size(84.dp).shadow(10.dp, CircleShape, spotColor = MettleColors.Orange).clip(CircleShape).background(MettleColors.Orange)
                .clickable(role = Role.Button, onClickLabel = "Pause workout", onClick = onPause)
                .semantics { contentDescription = "Pause" }, contentAlignment = Alignment.Center) {
                Icon(MettleIcons.Pause, contentDescription = null, tint = MettleColors.Surface, modifier = Modifier.size(34.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                SmallToggle(MettleIcons.Sun, if (screenOn) "Screen stays on" else "Screen can sleep", onScreen, active = screenOn)
            }
        }
    }
}

@Composable
private fun SmallToggle(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = true) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp).clip(CircleShape).background(if (active) MettleColors.Band else MettleColors.Surface)) {
        Icon(icon, contentDescription = label, tint = if (active) MettleColors.Ink else MettleColors.Muted)
    }
}

@Composable
private fun RoundAction(label: String, icon: ImageVector, bg: Color, fg: Color, modifier: Modifier, outlined: Boolean = false, onClick: () -> Unit) {
    Row(modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(bg)
        .then(if (outlined) Modifier.border(2.dp, MettleColors.Ink, RoundedCornerShape(30.dp)) else Modifier)
        .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Text("  " + label.uppercase(), style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Shared pill button (stair recording uses it). */
@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(60.dp).shadow(if (bg == MettleColors.Orange) 8.dp else 0.dp, RoundedCornerShape(30.dp), spotColor = MettleColors.Orange)
            .clip(RoundedCornerShape(30.dp)).background(if (bg == MettleColors.Surface) MettleColors.Band else bg)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = fg) }
}
