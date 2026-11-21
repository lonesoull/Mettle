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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mettle.data.db.ActivityType
import app.mettle.data.db.Visibility
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.workout.SaveDetails
import app.mettle.workout.WorkoutSaver
import kotlinx.coroutines.launch

private val effortWords = listOf("", "Very easy", "Easy", "Easy", "Moderate", "Moderate", "Hard", "Hard", "Very hard", "Very hard", "All out")

@Composable
fun SaveWorkoutScreen(sessionId: String, type: ActivityType, startedAt: Long, onSaved: (activityId: String) -> Unit, onDiscarded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf(WorkoutSaver.defaultTitle(type, startedAt)) }
    var notes by rememberSaveable { mutableStateOf("") }
    var rpe by rememberSaveable { mutableStateOf(5) }
    var shared by rememberSaveable { mutableStateOf(true) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MettleColors.Orange, focusedLabelColor = MettleColors.Orange, cursorColor = MettleColors.Orange)

    Column(Modifier.fillMaxSize().background(MettleColors.Surface).imePadding()) {
        TopBar("Save activity") { TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard", color = MettleColors.WarnText) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it.take(1000) }, label = { Text("How did it go?") }, colors = colors, modifier = Modifier.fillMaxWidth().height(110.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Perceived effort", style = MaterialTheme.typography.titleSmall)
                    Text("$rpe · ${effortWords[rpe]}", style = MaterialTheme.typography.titleSmall, color = MettleColors.Orange)
                }
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    (1..10).forEach { v ->
                        Box(Modifier.weight(1f).height((14 + v * 4).dp).clip(RoundedCornerShape(5.dp))
                            .background(if (v <= rpe) MettleColors.Orange.copy(alpha = 0.35f + 0.65f * v / rpe) else MettleColors.Line)
                            .clickable(role = Role.Button) { rpe = v })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("Easy", "Moderate", "All out").forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MettleColors.Sub) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share with your group", style = MaterialTheme.typography.titleSmall)
                    Text(if (shared) "Group sees it; start and end are hidden" else "Only you (and the admin) can see it",
                        style = MaterialTheme.typography.bodySmall, color = MettleColors.Sub)
                }
                Switch(shared, { shared = it }, colors = SwitchDefaults.colors(checkedTrackColor = MettleColors.Good, uncheckedTrackColor = MettleColors.Band, uncheckedThumbColor = MettleColors.Muted, uncheckedBorderColor = MettleColors.Muted))
            }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Pill(if (busy) "SAVING…" else "SAVE ACTIVITY", MettleColors.Orange, MettleColors.Surface, Modifier.fillMaxWidth()) {
                if (busy) return@Pill
                busy = true
                scope.launch {
                    val id = WorkoutSaver.save(context, sessionId, type, startedAt,
                        SaveDetails(title, notes, rpe, if (shared) Visibility.GROUP else Visibility.PRIVATE))
                    onSaved(id)
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this workout?") },
            text = { Text("The recording will be deleted from your phone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { scope.launch { WorkoutSaver.discard(context, sessionId); onDiscarded() } }) { Text("Discard", color = MettleColors.WarnText) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}
