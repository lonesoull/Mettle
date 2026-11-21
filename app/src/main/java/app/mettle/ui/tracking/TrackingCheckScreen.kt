package app.mettle.ui.tracking

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mettle.BuildConfig
import app.mettle.MettleApplication
import app.mettle.data.db.MettleDatabase
import app.mettle.tracking.Diag
import app.mettle.tracking.TrackingService
import app.mettle.tracking.TrackingState
import app.mettle.ui.components.PrimaryButton
import app.mettle.ui.components.SecondaryButton
import app.mettle.ui.components.Section
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class Today(val steps: Int, val trackedMin: Int, val elapsedMin: Int, val points: Int, val floors: Int)

/**
 * Is all-day tracking actually working on this phone? Every setting, the tracker's heartbeat,
 * last location and activity, today's coverage, and a log of what happened — plus a file to share.
 */
@Composable
fun TrackingCheckScreen(db: MettleDatabase, onSetup: () -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val status = remember(refresh) { setupStatus(c) }
    val enabled = remember(refresh) { TrackingService.isEnabled(c) }
    val heartbeat = remember(refresh) { c.getSharedPreferences("tracking", Context.MODE_PRIVATE).getLong("heartbeat", 0L) }
    val today by produceState<Today?>(null, refresh) { value = withContext(Dispatchers.IO) { todayStats(db) } }
    val events = remember(refresh) { Diag.events(c).takeLast(40).reversed() }
    val sensors = remember { c.getSystemService(SensorManager::class.java) }
    var sharing by remember { mutableStateOf(false) }

    val beatAgeMin = if (heartbeat > 0) (System.currentTimeMillis() - heartbeat) / 60_000 else null
    val alive = enabled && TrackingState.running && beatAgeMin != null && beatAgeMin <= 3

    Column(Modifier.fillMaxSize().background(MettleColors.Band)) {
        TopBar("Tracking check", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Headline verdict
            Section {
                val (title, body, color) = when {
                    !enabled -> Triple("Tracking is off", "Turn on all-day tracking to record steps, places and floors.", MettleColors.WarnText)
                    !status.essentials -> Triple("Tracking is blocked", "A required permission is missing. Fix the red items below.", MettleColors.WarnText)
                    !alive && beatAgeMin == null -> Triple("Tracker hasn't started", "Tap Restart tracking. If it stops again, fix the items below.", MettleColors.WarnText)
                    !alive -> Triple("Tracker isn't running", "It last checked in $beatAgeMin min ago, so your phone stopped it. " +
                        "Set Battery to Unrestricted below so Android leaves it running.", MettleColors.WarnText)
                    !status.background || !status.battery -> Triple("Working, with limits", "Fix the amber items so tracking survives screen-off and app updates.", MettleColors.Warn)
                    else -> Triple("Everything is working", "Last check-in ${if (beatAgeMin == 0L) "just now" else "$beatAgeMin min ago"}.", MettleColors.Good)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp))
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                if (!enabled || !alive) PrimaryButton(if (enabled) "Restart tracking" else "Turn on tracking") {
                    if (status.essentials) { runCatching { TrackingService.start(c) }; refresh++ } else onSetup()
                }
            }

            Section(title = "Settings") {
                Check("Physical activity", if (status.activity) Level.OK else Level.BAD, "Needed for steps and activity type") { onSetup() }
                Check("Precise location", if (status.location) Level.OK else Level.BAD, "Needed for places and routes") { onSetup() }
                Check("Location all the time", if (status.background) Level.OK else Level.WARN, "Lets tracking continue with the screen off") { onSetup() }
                Check("Battery: Unrestricted", if (status.battery) Level.OK else Level.WARN, "Stops Android/Motorola from killing the tracker") { onSetup() }
                Check("Notifications", if (status.notifications) Level.OK else Level.WARN, "Shows the tracking notification") { onSetup() }
                if (Build.MANUFACTURER.equals("motorola", true)) Text(
                    "Motorola also has its own setting: Settings → Apps → Mettle → App battery usage → Unrestricted, and turn off \"Pause app activity if unused\".",
                    style = MettleType.Label, color = MettleColors.Sub,
                )
                TextButton(onClick = {
                    runCatching { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open Mettle's Android settings", color = MettleColors.Orange) }
            }

            Section(title = "Right now") {
                Fact("Tracker", if (alive) "Running" else if (enabled) "Not running" else "Off")
                Fact("Last check-in", beatAgeMin?.let { if (it == 0L) "Just now" else "$it min ago" } ?: "Never")
                Diag.get(c, "fix")?.let { (v, t) -> Fact("Last location", "$v · ${ago(t)}") } ?: Fact("Last location", "None yet")
                Diag.get(c, "activity")?.let { (v, t) -> Fact("Android detected", "$v · ${ago(t)}") } ?: Fact("Android detected", "Nothing yet")
                Fact("Step sensor", if (sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) "Yes" else "No (steps less accurate)")
                Fact("Barometer (floors)", if (sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) "Yes" else "No (floors can't be counted)")
            }

            Section(title = "Today so far") {
                val t = today
                if (t == null) Text("Loading…", style = MettleType.Label, color = MettleColors.Sub)
                else {
                    val cover = if (t.elapsedMin > 0) (t.trackedMin * 100 / t.elapsedMin).coerceAtMost(100) else 0
                    Fact("Time covered", "$cover% (${t.trackedMin / 60} h ${t.trackedMin % 60} min of ${t.elapsedMin / 60} h ${t.elapsedMin % 60} min)")
                    Fact("Steps", "%,d".format(t.steps))
                    Fact("Location points", "${t.points}")
                    Fact("Floors climbed", "${t.floors}")
                    if (cover < 80) Text("Under 80% means the tracker was stopped for part of the day. The log below shows when.",
                        style = MettleType.Label, color = MettleColors.WarnText)
                }
            }

            Section(title = "What happened") {
                if (events.isEmpty()) Text("No events yet.", style = MettleType.Label, color = MettleColors.Sub)
                val f = DateTimeFormatter.ofPattern("d MMM h:mm a")
                events.forEach { (t, e) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(f), style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.width(118.dp))
                        Text(e, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }

            Section(title = "Help fix tracking") {
                Text("Saves today's tracking data and this log to a file you can send to Claude, so detection can be tuned to your phone. It stays on your phone until you share it.",
                    style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                SecondaryButton(if (sharing) "Preparing…" else "Send diagnostics", enabled = !sharing) {
                    sharing = true
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { runCatching { diagnosticsFile(c, db) }.getOrNull() }
                        sharing = false
                        file?.let {
                            val uri = FileProvider.getUriForFile(c, c.packageName + ".files", it)
                            c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send diagnostics"))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private enum class Level { OK, WARN, BAD }

@Composable
private fun Check(title: String, level: Level, why: String, onFix: () -> Unit) {
    val color = when (level) { Level.OK -> MettleColors.Good; Level.WARN -> MettleColors.Warn; Level.BAD -> MettleColors.WarnText }
    val word = when (level) { Level.OK -> "OK"; Level.WARN -> "Recommended"; Level.BAD -> "Required" }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = "$title: ${if (level == Level.OK) "OK" else "needs fixing"}" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (level == Level.OK) why else "$word · $why", style = MettleType.Label, color = if (level == Level.OK) MettleColors.Sub else color)
        }
        if (level != Level.OK) TextButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fix", color = MettleColors.Orange) }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Color.Unspecified)
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
}

private suspend fun todayStats(db: MettleDatabase): Today {
    val zone = ZoneId.systemDefault()
    val day = LocalDate.now(zone)
    val rows = db.day().minutesNow(day.toString())
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val pts = db.track().between(start, System.currentTimeMillis())
    val now = LocalTime.now(zone)
    return Today(rows.sumOf { it.steps }, rows.size, now.hour * 60 + now.minute + 1, pts.size, rows.sumOf { it.floorsUp })
}

/** JSON file: phone, settings, the event log, today's minutes and location points. */
private suspend fun diagnosticsFile(c: Context, db: MettleDatabase): File {
    val zone = ZoneId.systemDefault()
    val day = LocalDate.now(zone)
    val s = setupStatus(c)
    val sm = c.getSystemService(SensorManager::class.java)
    val root = JSONObject()
        .put("app", BuildConfig.VERSION_NAME).put("phone", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.SDK_INT)
        .put("timezone", zone.id).put("created", Instant.now().toString())
        .put("settings", JSONObject().put("activity", s.activity).put("location", s.location).put("background", s.background)
            .put("battery_unrestricted", s.battery).put("notifications", s.notifications).put("tracking_on", TrackingService.isEnabled(c))
            .put("running", TrackingState.running))
        .put("sensors", JSONObject().put("step_counter", sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null)
            .put("barometer", sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null))
        .put("events", JSONArray().also { a -> Diag.events(c).forEach { (t, e) -> a.put(JSONObject().put("t", Instant.ofEpochMilli(t).toString()).put("e", e)) } })
    val minutes = JSONArray()
    for (d in listOf(day.minusDays(1), day)) db.day().minutesNow(d.toString()).forEach { m ->
        minutes.put(JSONObject().put("day", m.day).put("min", m.minute).put("steps", m.steps).put("state", m.state.name.lowercase())
            .put("up", m.floorsUp).put("down", m.floorsDown).put("lift", m.liftTrips))
    }
    root.put("minutes", minutes)
    val points = JSONArray()
    db.track().between(day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis()).forEach { p ->
        points.put(JSONObject().put("t", Instant.ofEpochMilli(p.tMs).toString()).put("lat", "%.5f".format(java.util.Locale.US, p.lat))
            .put("lon", "%.5f".format(java.util.Locale.US, p.lon)).put("acc", p.accuracyM).put("alt", p.altitudeM).put("state", p.state?.name?.lowercase())
            .put("hpa", p.pressureHpa))
    }
    root.put("points", points)
    return File(c.cacheDir, "exports").apply { mkdirs() }.let { File(it, "mettle-diagnostics-$day.json") }.also { it.writeText(root.toString(1)) }
}
