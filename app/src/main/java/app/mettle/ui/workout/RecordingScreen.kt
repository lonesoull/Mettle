package app.mettle.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mettle.ui.Fmt
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import app.mettle.workout.WorkoutService

@Composable
fun RecordingScreen(onFinished: (sessionId: String) -> Unit) {
    val context = LocalContext.current
    val live by WorkoutService.live.collectAsState()
    LaunchedEffect(live.recording, live.sessionId) {
        if (!live.recording && live.sessionId != null && live.startedAt > 0) onFinished(live.sessionId!!)
    }
    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            RouteMap(live.route, follow = true, modifier = Modifier.fillMaxSize())
            Row(Modifier.statusBarsPadding().padding(12.dp).clip(RoundedCornerShape(20.dp)).background(MettleColors.Surface).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (label, color) = when {
                    live.paused -> "Paused" to MettleColors.Warn
                    live.autoPaused -> "Auto-paused" to MettleColors.Warn
                    else -> "Recording" to MettleColors.Orange
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                Text(label, style = MaterialTheme.typography.titleSmall, color = color)
                Text(live.gpsAccuracyM?.let { "· GPS ±${it.toInt()} m" } ?: "· Waiting for GPS…", style = MettleType.Label, color = MettleColors.Sub)
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TIME", style = MettleType.Overline, color = MettleColors.Sub)
                Text(Fmt.duration(live.movingS.toLong()), style = MettleType.Hero)
            }
            Row(Modifier.fillMaxWidth()) {
                Big("km", Fmt.km2(live.distanceM.toLong()), Modifier.weight(1f))
                Big("pace /km", Fmt.pace(live.currentPaceSKm), Modifier.weight(1f), MettleColors.Orange)
                Big("avg /km", Fmt.pace(live.avgPaceSKm), Modifier.weight(1f))
            }
            Text("Climb ${live.elevGainM.toInt()} m · Elapsed ${Fmt.duration(live.elapsedS.toLong())}",
                style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (live.paused) {
                    Pill("RESUME", MettleColors.Surface, MettleColors.Ink, Modifier.weight(1f)) { WorkoutService.resume(context) }
                    Pill("FINISH", MettleColors.Orange, MettleColors.Surface, Modifier.weight(1f)) { WorkoutService.finish(context) }
                } else {
                    Pill("PAUSE", MettleColors.Orange, MettleColors.Surface, Modifier.weight(1f)) { WorkoutService.pause(context) }
                }
            }
        }
    }
}

@Composable
private fun Big(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = MettleColors.Ink) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MettleType.StatLarge, color = color)
        Text(label, style = MettleType.Label, color = MettleColors.Sub)
    }
}

@Composable
fun Pill(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(60.dp).shadow(if (bg == MettleColors.Orange) 8.dp else 0.dp, RoundedCornerShape(30.dp), spotColor = MettleColors.Orange)
            .clip(RoundedCornerShape(30.dp)).background(bg)
            .then(if (bg == MettleColors.Surface) Modifier.background(MettleColors.Band) else Modifier)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = fg) }
}
