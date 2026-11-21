package app.mettle.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mettle.BuildConfig
import app.mettle.data.remote.Backend
import app.mettle.data.remote.Profile
import app.mettle.sync.ProfileCache
import app.mettle.ui.components.ListRow
import app.mettle.ui.components.Space
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.home.SOS_RED
import kotlinx.coroutines.launch

/** Profile and every setting in one place. */
@Composable
fun YouScreen(
    profile: Profile, onEditProfile: () -> Unit, onSafety: () -> Unit, onHealth: () -> Unit, onTracking: () -> Unit,
    onTransfer: () -> Unit, onSensorLab: () -> Unit, onAdmin: () -> Unit, onSignedOut: () -> Unit,
    onTrackingCheck: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MettleColors.Band)) {
        TopBar("You")
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.fillMaxWidth().background(MettleColors.Surface).padding(Space.l), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(MettleColors.OrangeTint), contentAlignment = Alignment.Center) {
                    Text(profile.displayName.take(1).uppercase(), style = MaterialTheme.typography.headlineLarge, color = MettleColors.OrangeDeep)
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.headlineMedium)
                    Text((if (profile.isAdmin) "Group admin · " else "") + "${profile.heightCm} cm" + (profile.weightKg?.let { " · $it kg" } ?: "") +
                        " · ${"%,d".format(profile.stepGoal)} steps/day", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
                }
                TextButton(onClick = onEditProfile) { Text("Edit", color = MettleColors.Orange) }
            }
            Group {
                ListRow("Safety & SOS", "Fall detection, SOS, emergency contacts", MettleIcons.Shield, SOS_RED, onClick = onSafety)
                Divider()
                ListRow("Health & devices", "Heart rate, readiness, straps, sleep", MettleIcons.Heart, MettleColors.Orange, onClick = onHealth)
                Divider()
                ListRow("All-day tracking", "Permissions and battery settings", MettleIcons.Activity, MettleColors.Good, onClick = onTracking)
                Divider()
                ListRow("Tracking check", "Is tracking working? Fix problems, send diagnostics", MettleIcons.Info, MettleColors.Descent, onClick = onTrackingCheck)
            }
            Group {
                ListRow("Import & export", "GPX files, Strava archive, CSV", MettleIcons.Swap, MettleColors.Descent, onClick = onTransfer)
                Divider()
                ListRow("Sensor Lab", "Help Mettle learn stairs on your phone", MettleIcons.Lab, MettleColors.Ink, onClick = onSensorLab)
                if (profile.isAdmin) { Divider(); ListRow("Admin dashboard", "Live group map, alerts, members", MettleIcons.Team, MettleColors.OrangeDeep, onClick = onAdmin) }
            }
            Group {
                ListRow("App version", null, MettleIcons.Info, MettleColors.Sub, value = BuildConfig.VERSION_NAME)
                Divider()
                ListRow("Sign out", null, MettleIcons.Logout, MettleColors.Sub, onClick = { confirmSignOut = true })
            }
        }
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("Tracking stops until you sign in again. Workouts already on this phone are kept.") },
        confirmButton = { TextButton(onClick = {
            confirmSignOut = false; ProfileCache.clear(ctx); app.mettle.tracking.TrackingService.stop(ctx)
            scope.launch { runCatching { Backend.signOut() }; onSignedOut() }
        }) { Text("Sign out", color = MettleColors.WarnText) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) { Column(Modifier.fillMaxWidth().background(MettleColors.Surface)) { content() } }

@Composable
private fun Divider() = HorizontalDivider(color = MettleColors.Line, modifier = Modifier.padding(start = 72.dp))
