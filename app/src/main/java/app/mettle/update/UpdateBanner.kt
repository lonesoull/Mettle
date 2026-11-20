package app.mettle.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mettle.ui.theme.MettleColors
import kotlinx.coroutines.launch

/** Orange bar shown at the top of the app when a newer version exists. */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<Updater.Available?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf(0) }

    LifecycleResumeEffect(Unit) { checks++; onPauseOrDispose { } }
    LaunchedEffect(checks) { if (progress == null) available = Updater.check() }

    val update = available ?: return
    Column(
        Modifier.fillMaxWidth().background(MettleColors.Ink).statusBarsPadding()
            .clickable(enabled = progress == null, role = Role.Button) {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
                    message = "Allow Mettle to install updates, then tap again."
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return@clickable
                }
                message = null
                progress = 0f
                scope.launch {
                    runCatching { Updater.install(context, update) { p -> progress = p } }
                        .onFailure { progress = null; message = "Update failed. Check your connection and tap to try again." }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (val p = progress) {
                    null -> "Update to Mettle ${update.versionName} — tap to install"
                    else -> if (p < 1f) "Downloading update… ${(p * 100).toInt()}%" else "Installing…"
                },
                style = MaterialTheme.typography.titleSmall, color = MettleColors.Surface,
            )
        }
        progress?.let {
            LinearProgressIndicator(
                progress = { it }, color = MettleColors.Orange, trackColor = MettleColors.Ink2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MettleColors.OrangeTint, modifier = Modifier.padding(top = 4.dp)) }
    }
}
