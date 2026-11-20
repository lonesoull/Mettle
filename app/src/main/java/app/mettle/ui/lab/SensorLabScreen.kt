package app.mettle.ui.lab

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mettle.data.remote.Backend
import app.mettle.ui.Fmt
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import app.mettle.ui.workout.Pill
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

@Serializable
private data class LabRow(val id: String, @SerialName("user_id") val userId: String, val device: String, val labels: JsonArray,
                          @SerialName("file_path") val filePath: String, val notes: String?)

private val LABELS = listOf("up" to "▲ Climbing stairs", "down" to "▼ Going down stairs", "flat" to "Walking on flat ground",
    "lift_up" to "Lift going up", "lift_down" to "Lift going down", "standing" to "Standing", "sitting" to "Sitting", "rest" to "Resting / other")

/**
 * Sensor Lab: records raw motion (accelerometer, gyroscope, barometer at 50 Hz)
 * while you tap what you're doing. Uploaded to train stair detection for phones
 * without a barometer.
 */
@Composable
fun SensorLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sm = remember { context.getSystemService(SensorManager::class.java) }
    var recording by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<String?>(null) }
    var startMs by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val rows = remember { StringBuilder() }
    val segments = remember { ArrayList<Triple<String, Long, Long?>>() }

    DisposableEffect(recording) {
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                val kind = when (e.sensor.type) { Sensor.TYPE_ACCELEROMETER -> "a"; Sensor.TYPE_GYROSCOPE -> "g"; Sensor.TYPE_PRESSURE -> "p"; Sensor.TYPE_STEP_DETECTOR -> "s"; else -> return }
                synchronized(rows) { rows.append(e.timestamp).append(',').append(kind).append(',').append(e.values.take(3).joinToString(",")).append('\n') }
            }
        }
        if (recording) listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_PRESSURE, Sensor.TYPE_STEP_DETECTOR)
            .forEach { t -> sm.getDefaultSensor(t)?.let { sm.registerListener(listener, it, 20_000) } }
        onDispose { sm.unregisterListener(listener) }
    }
    LaunchedEffect(recording) { while (recording) { elapsed = ((System.currentTimeMillis() - startMs) / 1000).toInt(); delay(500) } }

    fun label(key: String) {
        val now = System.currentTimeMillis()
        if (segments.isNotEmpty() && segments.last().third == null) segments[segments.lastIndex] = segments.last().copy(third = now)
        segments += Triple(key, now, null); current = key
    }

    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        TopBar("Sensor Lab")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Helps Mettle learn stairs on your phone. Keep the phone where you normally carry it, press Start, then tap what you're doing each time it changes. 10–15 minutes of mixed stairs is ideal.",
                style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)
            if (recording) Text("${Fmt.duration(elapsed.toLong())} · ${segments.size} labels", style = MettleType.StatMedium)
            LABELS.forEach { (key, text) ->
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (current == key) MettleColors.Orange else MettleColors.Band)
                    .clickable(enabled = recording, role = Role.Button) { label(key) }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(text, style = MaterialTheme.typography.titleMedium, color = if (current == key) MettleColors.Surface else if (recording) MettleColors.Ink else MettleColors.Muted)
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub) }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!recording) Pill("START", MettleColors.Orange, MettleColors.Surface, Modifier.weight(1f)) {
                synchronized(rows) { rows.setLength(0) }; segments.clear(); current = null; status = null
                startMs = System.currentTimeMillis(); recording = true
            } else Pill("STOP & UPLOAD", MettleColors.Orange, MettleColors.Surface, Modifier.weight(1f)) {
                recording = false
                if (segments.isNotEmpty() && segments.last().third == null) segments[segments.lastIndex] = segments.last().copy(third = System.currentTimeMillis())
                status = "Uploading…"
                scope.launch {
                    status = runCatching {
                        val userId = Backend.client.auth.currentUserOrNull()?.id ?: error("signed out")
                        val id = UUID.randomUUID().toString()
                        val bytes = withContext(Dispatchers.IO) {
                            val out = ByteArrayOutputStream()
                            GZIPOutputStream(out).use { it.write(("ts_ns,sensor,x,y,z\n" + synchronized(rows) { rows.toString() }).toByteArray()) }
                            out.toByteArray()
                        }
                        val path = "$userId/$id.csv.gz"
                        Backend.client.storage.from("sensorlab").upload(path, bytes) { upsert = true }
                        val labels = buildJsonArray { segments.forEach { (k, s, e) -> add(buildJsonObject { put("label", k); put("t_start", s); put("t_end", e ?: s) }) } }
                        Backend.client.from("sensor_lab_recordings").insert(LabRow(id, userId, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", labels, path, null))
                        "Uploaded ${bytes.size / 1024} KB. Thank you — this improves stair detection."
                    }.getOrElse { "Upload failed (${it.message?.take(60)}). Check your connection and record again." }
                }
            }
        }
    }
}
