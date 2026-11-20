package app.mettle.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mettle.data.db.MettleDatabase
import app.mettle.data.remote.Backend
import app.mettle.data.remote.Profile
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import app.mettle.ui.components.Section
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors

@Composable
fun ProgressScreen(db: MettleDatabase, profile: Profile, onSensorLab: () -> Unit = {}, onAdmin: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val recent by db.activities().recent(1).collectAsState(emptyList())
    Column(Modifier.fillMaxSize().background(MettleColors.Band), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TopBar("Progress")
        Section {
            Text("Fitness & freshness", style = MaterialTheme.typography.titleLarge)
            Text(
                if (recent.isEmpty()) "Your fitness, fatigue and form charts start filling in after your first few workouts."
                else "Keep going. Charts appear once there is a week of training to analyse.",
                style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2,
            )
        }
        Section {
            Text(if (profile.isAdmin) "${profile.displayName} · Admin" else profile.displayName, style = MaterialTheme.typography.titleLarge)
            Text("Height ${profile.heightCm} cm" + (profile.weightKg?.let { " · Weight $it kg" } ?: "") + " · Step goal ${profile.stepGoal}",
                style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)
            if (profile.isAdmin) TextButton(onClick = onAdmin) { Text("Admin dashboard", color = MettleColors.Orange) }
            TextButton(onClick = onSensorLab) { Text("Sensor Lab (improve stair detection)", color = MettleColors.Orange) }
            TextButton(onClick = { app.mettle.sync.ProfileCache.clear(ctx); scope.launch { runCatching { Backend.signOut() } } }) { Text("Sign out", color = MettleColors.Orange) }
        }
        Section {
            Text("Personal records", style = MaterialTheme.typography.titleLarge)
            Text("Your fastest 400 m, 1 km, 5 km and stair efforts will be listed here automatically.",
                style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
