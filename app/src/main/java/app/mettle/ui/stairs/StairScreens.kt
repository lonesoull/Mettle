package app.mettle.ui.stairs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.mettle.data.db.MettleDatabase
import app.mettle.data.db.PlaceEntity
import app.mettle.data.db.SyncState
import app.mettle.ui.Fmt
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import app.mettle.ui.workout.Pill
import app.mettle.workout.WorkoutMath
import app.mettle.workout.WorkoutService
import kotlinx.coroutines.launch
import java.util.UUID

/** Building picker shown on the Record screen when Stairs is selected. */
@Composable
fun BuildingPicker(db: MettleDatabase, selectedId: String?, onSelect: (String?) -> Unit, onCalibrate: (String) -> Unit) {
    val places by db.places().all().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(places) { if (selectedId == null && places.isNotEmpty()) onSelect(places.first().id) }
    val selected = places.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            places.forEach { p ->
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (p.id == selectedId) MettleColors.Ink else MettleColors.Band)
                    .clickable(role = Role.RadioButton) { onSelect(p.id) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, color = if (p.id == selectedId) MettleColors.Surface else MettleColors.Ink)
                }
            }
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(MettleColors.OrangeWash).clickable(role = Role.Button) { adding = true }
                .padding(horizontal = 14.dp, vertical = 9.dp)) { Text("+ Building", style = MaterialTheme.typography.titleSmall, color = MettleColors.Orange) }
        }
        if (selected != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected.stepsPerFloor != null) "${selected.stepsPerFloor} steps per floor · ${"%.1f".format(selected.floorHeightM)} m floors"
                else "Not calibrated yet — floors use a typical 22 steps",
                style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onCalibrate(selected.id) }) { Text(if (selected.stepsPerFloor != null) "Recalibrate" else "Calibrate", color = MettleColors.Orange) }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var height by remember { mutableStateOf("3.0") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a building") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name, e.g. Office Block B") }, singleLine = true)
                    OutlinedTextField(height, { height = it.take(4) }, label = { Text("Floor height (m), if known") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val id = UUID.randomUUID().toString()
                    scope.launch {
                        db.places().upsert(PlaceEntity(id, name.trim(), null, null, height.replace(',', '.').toDoubleOrNull()?.coerceIn(2.0, 6.0) ?: 3.0,
                            updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY))
                        onSelect(id)
                    }
                    adding = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

/** Climb exactly one floor while Mettle counts your steps (and measures the height on barometer phones). */
@Composable
fun CalibrationScreen(db: MettleDatabase, placeId: String, onDone: () -> Unit) {
    val onBack = onDone
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableIntStateOf(0) }
    var startAlt by remember { mutableStateOf<Double?>(null) }
    var nowAlt by remember { mutableStateOf<Double?>(null) }
    val sm = remember { context.getSystemService(SensorManager::class.java) }
    DisposableEffect(running) {
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR && running) steps++
                if (e.sensor.type == Sensor.TYPE_PRESSURE) {
                    val a = WorkoutMath.pressureAltitudeM(e.values[0])
                    nowAlt = if (nowAlt == null) a else nowAlt!! + 0.3 * (a - nowAlt!!)
                    if (!running) startAlt = nowAlt
                }
            }
        }
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }
    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        TopBar("Calibrate one floor", onBack = onBack)
        Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (!running) "1. Stand at the bottom of a flight of stairs.\n2. Tap Start.\n3. Climb exactly ONE floor at your normal pace.\n4. Tap Done at the next floor's landing."
                else "Climb one floor, then tap Done.", style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)
            Text("$steps", style = MettleType.Hero, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("steps", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (running && startAlt != null && nowAlt != null) {
                Text("Height climbed: ${"%.1f".format(nowAlt!! - startAlt!!)} m", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            if (!running) Pill("START", MettleColors.Orange, MettleColors.Surface, Modifier.fillMaxWidth()) { steps = 0; running = true }
            else Pill("DONE", MettleColors.Orange, MettleColors.Surface, Modifier.fillMaxWidth()) {
                val climbed = if (startAlt != null && nowAlt != null) nowAlt!! - startAlt!! else null
                scope.launch {
                    db.places().get(placeId)?.let { p ->
                        db.places().upsert(p.copy(
                            stepsPerFloor = steps.coerceIn(6, 60),
                            floorHeightM = climbed?.takeIf { it in 2.0..6.0 } ?: p.floorHeightM,
                            calibratedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), syncState = SyncState.DIRTY,
                        ))
                    }
                    onDone()
                }
            }
        }
    }
}

/** Live stair session. */
@Composable
fun StairRecordingScreen(onFinished: (String) -> Unit) {
    val context = LocalContext.current
    val live by WorkoutService.live.collectAsState()
    LaunchedEffect(live.recording, live.sessionId) {
        if (!live.recording && live.sessionId != null && live.startedAt > 0) onFinished(live.sessionId!!)
    }
    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        TopBar(if (live.machine) "Stair machine" else "Stairs")
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(when (live.climbing) { true -> "▲ Climbing · set ${live.setNo}"; false -> "▼ Going down · set ${live.setNo}"; null -> "Resting · set ${live.setNo}" },
                style = MaterialTheme.typography.titleMedium, color = when (live.climbing) { true -> MettleColors.Orange; false -> MettleColors.Descent; null -> MettleColors.Sub })
            Text(Fmt.duration(live.elapsedS.toLong()), style = MettleType.Hero)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MettleColors.OrangeWash).padding(14.dp)) {
                    Column { Text("▲ Floors up", style = MaterialTheme.typography.titleSmall, color = MettleColors.Orange); Text("${live.floorsUp}", style = MettleType.StatLarge) }
                }
                if (!live.machine) Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MettleColors.DescentTint).padding(14.dp)) {
                    Column { Text("▼ Floors down", style = MaterialTheme.typography.titleSmall, color = MettleColors.Descent); Text("${live.floorsDown}", style = MettleType.StatLarge) }
                }
            }
            Text("${live.steps} steps" + if (live.hasBarometer) " · barometer on" else " · floors from your steps", style = MettleType.Label, color = MettleColors.Sub)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(live.sets.reversed()) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("Set ${s.index}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("▲ ${s.floorsUp} · ${Fmt.duration(s.climbS.toLong())}", style = MaterialTheme.typography.titleSmall, color = MettleColors.Orange, modifier = Modifier.weight(1.3f))
                    if (s.floorsDown > 0) Text("▼ ${s.floorsDown} · ${Fmt.duration(s.descentS.toLong())}", style = MaterialTheme.typography.titleSmall, color = MettleColors.Descent, modifier = Modifier.weight(1.3f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (live.paused) {
                Pill("RESUME", MettleColors.Surface, MettleColors.Ink, Modifier.weight(1f)) { WorkoutService.resume(context) }
                Pill("FINISH", MettleColors.Orange, MettleColors.Surface, Modifier.weight(1f)) { WorkoutService.finish(context) }
            } else {
                Pill("NEW SET", MettleColors.Surface, MettleColors.Ink, Modifier.weight(1f)) { WorkoutService.newSet(context) }
                Pill("PAUSE", MettleColors.Orange, MettleColors.Surface, Modifier.weight(1f)) { WorkoutService.pause(context) }
            }
        }
    }
}
