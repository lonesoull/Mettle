package app.mettle.ui.record

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mettle.data.db.ActivityType
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons

private data class Mode(val type: ActivityType, val label: String, val icon: ImageVector)

private val modes = listOf(
    Mode(ActivityType.RUN, "Run", MettleIcons.Run),
    Mode(ActivityType.WALK, "Walk", MettleIcons.Walk),
    Mode(ActivityType.HIKE, "Hike", MettleIcons.Hike),
    Mode(ActivityType.STAIRS, "Stairs", MettleIcons.Stairs),
)

@Composable
fun RecordScreen(onClose: () -> Unit, onStarted: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(ActivityType.RUN) }
    var machine by rememberSaveable { mutableStateOf(false) }
    var showNotice by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) { app.mettle.workout.WorkoutService.start(context, selected); onStarted() }
    }

    Column(Modifier.fillMaxSize().background(MettleColors.Surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(MettleIcons.Close, contentDescription = "Close") }
            Spacer(Modifier.weight(1f))
            Text(if (selected == ActivityType.STAIRS) "Motion sensors ready" else "Waiting for GPS…",
                style = MaterialTheme.typography.titleSmall, color = MettleColors.Sub, modifier = Modifier.padding(end = 12.dp))
        }
        // The live map arrives with GPS recording in the next build.
        Box(Modifier.fillMaxWidth().weight(1f).background(MettleColors.Band), contentAlignment = Alignment.Center) {
            Text("Map", style = MaterialTheme.typography.titleMedium, color = MettleColors.Muted)
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { m -> ModeChip(m, m.type == selected, Modifier.weight(1f)) { selected = m.type } }
            }
            if (selected == ActivityType.STAIRS) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MettleColors.Band).padding(3.dp)) {
                    Segment("Stairwell", !machine, Modifier.weight(1f)) { machine = false }
                    Segment("Stair machine", machine, Modifier.weight(1f)) { machine = true }
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(88.dp).shadow(12.dp, CircleShape, spotColor = MettleColors.Orange).clip(CircleShape)
                        .background(MettleColors.Orange).clickable(role = Role.Button) {
                            if (selected == ActivityType.STAIRS) { showNotice = true; return@clickable }
                            val ok = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (ok) { app.mettle.workout.WorkoutService.start(context, selected); onStarted() }
                            else askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("START", style = MaterialTheme.typography.labelLarge, color = MettleColors.Surface) }
            }
        }
    }

    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            confirmButton = { TextButton(onClick = { showNotice = false }) { Text("OK") } },
            title = { Text("Stair workouts are coming next") },
            text = { Text("Run, walk and hike recording work now. Stair workouts (sets, floors up and down) arrive in the next update, which installs itself.") },
        )
    }
}

@Composable
private fun ModeChip(m: Mode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.height(84.dp).clip(shape)
            .background(if (selected) MettleColors.OrangeWash else MettleColors.Surface)
            .border(if (selected) BorderStroke(2.dp, MettleColors.Orange) else BorderStroke(1.dp, MettleColors.Line), shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (selected) MettleColors.Orange else MettleColors.Band), contentAlignment = Alignment.Center) {
            Icon(m.icon, contentDescription = null, tint = if (selected) MettleColors.Surface else MettleColors.Ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(m.label, style = MaterialTheme.typography.titleSmall, color = if (selected) MettleColors.Orange else MettleColors.Ink)
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
            .background(if (selected) MettleColors.Surface else MettleColors.Band)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) MettleColors.Ink else MettleColors.Sub) }
}
