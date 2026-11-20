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

/**
 * Automatic updates while the app is open: as soon as a newer version is found it
 * downloads and installs by itself. The bar only shows progress, or a one-time
 * request to let Mettle install updates.
 */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<Updater.Available?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf(0) }

    LifecycleResumeEffect(Unit) { checks++; onPauseOrDispose { } }
    LaunchedEffect(checks) {
        if (progress != null || Updater.installing) return@LaunchedEffect
        val update = Updater.check() ?: return@LaunchedEffect
        available = update
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            needsPermission = true
            return@LaunchedEffect
        }
        needsPermission = false
        failed = false
        progress = 0f
        scope.launch {
            runCatching { Updater.installOnce(context, update) { p -> progress = p } }
                .onFailure { progress = null; failed = true }
        }
    }

    val update = available ?: return
    if (progress == null && !needsPermission && !failed) return
    Column(
        Modifier.fillMaxWidth().background(MettleColors.Ink).statusBarsPadding()
            .clickable(enabled = needsPermission || failed, role = Role.Button) {
                if (needsPermission) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else checks++
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            when {
                needsPermission -> "One-time step: tap, then allow Mettle to install its own updates"
                failed -> "Update to ${update.versionName} didn't finish — tap to retry"
                (progress ?: 0f) < 1f -> "Updating Mettle to ${update.versionName}… ${((progress ?: 0f) * 100).toInt()}%"
                else -> "Installing update…"
            },
            style = MaterialTheme.typography.titleSmall, color = MettleColors.Surface,
        )
        progress?.let {
            LinearProgressIndicator(
                progress = { it }, color = MettleColors.Orange, trackColor = MettleColors.Ink2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}
