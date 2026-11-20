package app.mettle.ui.transfer

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.mettle.sync.SyncWorker
import app.mettle.transfer.Gpx
import app.mettle.ui.components.Section
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TransferScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        status = "Importing…"
        scope.launch {
            val (ok, skipped) = withContext(Dispatchers.IO) { Gpx.import(c, uris) { msg -> status = msg } }
            status = "Imported $ok workouts" + (if (skipped > 0) " · skipped $skipped files (FIT files and empty tracks aren't supported yet)" else "")
            SyncWorker.schedule(c)
        }
    }
    fun share(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }
    Column(Modifier.fillMaxSize().background(MettleColors.Band), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TopBar("Import & export") { IconButton(onClick = onBack) { Icon(MettleIcons.Close, contentDescription = "Close") } }
        Section {
            Text("Import", style = MaterialTheme.typography.titleLarge)
            Text("Choose .gpx files, or the whole Strava export .zip (Strava → Settings → My Account → Download your data). Imported workouts are private and get full analysis: splits, best efforts and records.",
                style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
            TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose files", color = MettleColors.Orange) }
            status?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        }
        Section {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { scope.launch { share(withContext(Dispatchers.IO) { Gpx.exportAll(c) }, "application/zip") } }) {
                Text("All workouts as GPX (.zip)", color = MettleColors.Orange) }
            TextButton(onClick = { scope.launch { share(withContext(Dispatchers.IO) { Gpx.exportDaysCsv(c) }, "text/csv") } }) {
                Text("Daily steps & floors (.csv)", color = MettleColors.Orange) }
        }
    }
}
