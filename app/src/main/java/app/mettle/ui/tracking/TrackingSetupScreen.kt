package app.mettle.ui.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mettle.sync.SyncWorker
import app.mettle.tracking.TrackingService
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors

private fun Context.has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

data class SetupStatus(val activity: Boolean, val notifications: Boolean, val location: Boolean, val background: Boolean, val battery: Boolean) {
    val essentials get() = activity && notifications
    val all get() = activity && notifications && location && background && battery
}

fun setupStatus(c: Context) = SetupStatus(
    activity = Build.VERSION.SDK_INT < 29 || c.has(Manifest.permission.ACTIVITY_RECOGNITION),
    notifications = Build.VERSION.SDK_INT < 33 || c.has(Manifest.permission.POST_NOTIFICATIONS),
    location = c.has(Manifest.permission.ACCESS_FINE_LOCATION),
    background = Build.VERSION.SDK_INT < 29 || c.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    battery = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
)

@SuppressLint("BatteryLife")
@Composable
fun TrackingSetupScreen(onDone: () -> Unit, onBack: () -> Unit = onDone) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val status = remember(refresh) { setupStatus(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        TopBar("All-day tracking", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mettle tracks your steps, activity and where you've been all day, using about 3–6% battery. Allow these once:",
                style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)

            Step(1, "Physical activity", "Counts steps and recognises walking, running and travelling.", status.activity) {
                if (Build.VERSION.SDK_INT >= 29) ask.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            }
            Step(2, "Notifications", "Android shows a small \"Mettle is tracking your day\" notification while tracking runs.", status.notifications) {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            Step(3, "Precise location", "Choose \"While using the app\" and keep \"Precise\" on.", status.location) {
                ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            Step(4, "Location all the time", "On the next screen choose \"Allow all the time\", so your day is tracked with the app closed.", status.background, enabled = status.location) {
                if (Build.VERSION.SDK_INT >= 29) ask.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
            Step(5, "Battery: unrestricted", "Stops your phone from pausing Mettle. On Motorola also check Settings → Apps → Mettle → App battery usage → Unrestricted.", status.battery) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }

            app.mettle.ui.components.PrimaryButton(if (status.all) "Start tracking" else "Start tracking (finish the rest later)", enabled = status.essentials) {
                TrackingService.start(context)
                SyncWorker.schedule(context)
                onDone()
            }
            if (!status.essentials) {
                Text("Steps 1 and 2 are needed to start. Location and battery make the tracking complete.",
                    style = MaterialTheme.typography.bodySmall, color = MettleColors.Sub)
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, detail: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (done) MettleColors.OrangeWash else MettleColors.Band)
            .clickable(enabled = !done && enabled, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (done) MettleColors.Good else if (enabled) MettleColors.Orange else MettleColors.Muted),
            contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", style = MaterialTheme.typography.titleSmall, color = MettleColors.Surface)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (done) "Done" else detail, style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
        }
        if (!done && enabled) Text("Allow", style = MaterialTheme.typography.titleSmall, color = MettleColors.Orange)
    }
}
