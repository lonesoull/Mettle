package app.mettle.ui.safety

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.mettle.safety.AlertUploadWorker
import app.mettle.safety.Contact
import app.mettle.safety.SafetyPrefs
import app.mettle.safety.SafetyState
import app.mettle.ui.components.Section
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SafetyScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var fall by remember { mutableStateOf(SafetyPrefs.fallDetection(c)) }
    var noMove by remember { mutableStateOf(SafetyPrefs.noMovement(c)) }
    var hours by remember { mutableIntStateOf(SafetyPrefs.noMovementHours(c)) }
    var window by remember { mutableStateOf(SafetyPrefs.noMovementFrom(c).toString() to SafetyPrefs.noMovementTo(c).toString()) }
    var contacts by remember { mutableStateOf(SafetyPrefs.contacts(c)) }
    var adding by remember { mutableStateOf(false) }
    var smsOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsOk = it }
    fun saveNoMove() { SafetyPrefs.setNoMovement(c, noMove, hours, window.first, window.second); AlertUploadWorker.enqueue(c) }

    Column(Modifier.fillMaxSize().background(MettleColors.Band)) {
        TopBar("Safety", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section {
                Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).background(Color_SOS)
                    .combinedClickable(onClick = {}, onLongClick = { SafetyState.startCountdown(c, "sos", seconds = 10) }),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SOS", style = MaterialTheme.typography.headlineLarge, color = MettleColors.Surface)
                        Text("Press and hold · 10 s to cancel", style = MaterialTheme.typography.bodySmall, color = MettleColors.Surface)
                    }
                }
                Text("Texts your emergency contacts with your location and alerts your group admin.", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
            }
            Section {
                ToggleRow("Fall detection", "A hard fall followed by stillness starts a 30-second \"Are you OK?\" countdown.", fall) {
                    fall = it; SafetyPrefs.setFallDetection(c, it); saveNoMove()
                }
            }
            Section {
                ToggleRow("No-movement alert", "If you don't move for the hours below, inside the chosen times, Mettle asks if you're OK, then alerts.", noMove) { noMove = it; saveNoMove() }
                if (noMove) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("After", style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { hours = (hours - 1).coerceAtLeast(1); saveNoMove() }) { Text("−", color = MettleColors.Orange) }
                        Text("$hours h", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { hours = (hours + 1).coerceAtMost(24); saveNoMove() }) { Text("+", color = MettleColors.Orange) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("07:00" to "22:00", "06:00" to "23:00", "00:00" to "23:59").forEach { w ->
                            val on = window == w
                            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) MettleColors.Ink else MettleColors.Band)
                                .combinedClickable(onClick = { window = w; saveNoMove() }).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(if (w.first == "00:00") "All day" else "${w.first}–${w.second}", style = MaterialTheme.typography.titleSmall,
                                    color = if (on) MettleColors.Surface else MettleColors.Ink)
                            }
                        }
                    }
                }
            }
            Section {
                Text("Emergency contacts", style = MaterialTheme.typography.titleLarge)
                if (!smsOk) TextButton(onClick = { askSms.launch(Manifest.permission.SEND_SMS) }) { Text("Allow Mettle to send emergency SMS", color = MettleColors.Orange) }
                contacts.forEach { ct ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(ct.name, style = MaterialTheme.typography.titleSmall); Text(ct.phone, style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub) }
                        TextButton(onClick = { contacts = contacts - ct; SafetyPrefs.setContacts(c, contacts); AlertUploadWorker.enqueue(c) }) { Text("Remove", color = MettleColors.Sub) }
                    }
                }
                if (contacts.size < 5) TextButton(onClick = { adding = true }) { Text("+ Add contact", color = MettleColors.Orange) }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Emergency contact") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' }.take(20) }, label = { Text("Phone, e.g. +91 98765 43210") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank() && phone.count { it.isDigit() } >= 8, onClick = {
                contacts = contacts + Contact(name.trim(), phone.trim()); SafetyPrefs.setContacts(c, contacts); AlertUploadWorker.enqueue(c); adding = false
            }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
}

private val Color_SOS = androidx.compose.ui.graphics.Color(0xFFDC2626)

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = MettleColors.Good, uncheckedTrackColor = MettleColors.Band, uncheckedThumbColor = MettleColors.Muted, uncheckedBorderColor = MettleColors.Muted))
    }
}
