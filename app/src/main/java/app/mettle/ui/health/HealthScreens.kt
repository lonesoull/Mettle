package app.mettle.ui.health

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import app.mettle.health.HealthMath
import app.mettle.health.HealthStore
import app.mettle.health.HeartRateBle
import app.mettle.health.SpotCheck
import app.mettle.sync.SyncWorker
import app.mettle.ui.components.Section
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.MettleType
import app.mettle.ui.workout.Pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.concurrent.Executors

fun readinessWord(r: Int) = when { r >= 75 -> "Ready to train hard"; r >= 50 -> "Good for steady training"; r >= 30 -> "Take it easy today"; else -> "Rest and recover" }

/** Health & devices. Sections appear only when the phone or a connected device can provide them. */
@Composable
fun HealthScreen(onFingertip: () -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = c.packageManager
    var refresh by remember { mutableStateOf(0) }
    val readiness by produceState<Int?>(null, refresh) { value = runCatching { HealthStore.readiness(c) }.getOrNull() }
    val hcGranted by produceState(false, refresh) { value = HealthStore.healthConnectGranted(c) }
    val lastSleep by produceState<app.mettle.health.Sleep?>(null, hcGranted) { value = if (hcGranted) HealthStore.recentSleep(c, 1).maxByOrNull { it.end } else null }
    val hcAsk = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++; SyncWorker.schedule(c) }
    val bleAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (HeartRateBle.hasPermission(c)) HeartRateBle.scan(c) }
    val found by HeartRateBle.found.collectAsState()
    val bpm by HeartRateBle.bpm.collectAsState()
    var strap by remember { mutableStateOf(HeartRateBle.saved(c)) }
    var scanning by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { HeartRateBle.stopScan(c) } }

    Column(Modifier.fillMaxSize().background(MettleColors.Band)) {
        TopBar("Health") { IconButton(onClick = onBack) { Icon(MettleIcons.Close, contentDescription = "Close") } }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            readiness?.let { r ->
                Section {
                    Text("Readiness", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("$r", style = MettleType.Hero, color = if (r >= 50) MettleColors.Good else MettleColors.Warn)
                        Text(readinessWord(r), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Text("From your morning heart rate vs your normal, last night's sleep (if a device provides it) and recent training load.",
                        style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                }
            }
            if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) Section {
                Text("Fingertip heart rate", style = MaterialTheme.typography.titleLarge)
                val last = HealthStore.spotChecks(c).lastOrNull()
                Text(last?.let { "Last: ${it.bpm} bpm" } ?: "Measure your resting heart rate with the camera. Best done each morning before getting up.",
                    style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                TextButton(onClick = onFingertip) { Text("Measure now", color = MettleColors.Orange) }
            }
            if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) Section {
                Text("Heart-rate strap or watch", style = MaterialTheme.typography.titleLarge)
                if (strap != null) {
                    Text("${strap!!.name}" + (bpm?.let { " · $it bpm now" } ?: " · connects during workouts"), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { HeartRateBle.save(c, null); HeartRateBle.disconnect(); strap = null }) { Text("Forget", color = MettleColors.Sub) }
                } else {
                    Text("Pair a Bluetooth chest strap, or a watch in heart-rate broadcast mode, to see heart rate during workouts.",
                        style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                    TextButton(onClick = {
                        scanning = true
                        if (HeartRateBle.hasPermission(c)) HeartRateBle.scan(c)
                        else if (Build.VERSION.SDK_INT >= 31) bleAsk.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    }) { Text(if (scanning) "Searching… (wear the strap)" else "Find my strap", color = MettleColors.Orange) }
                    found.forEach { f ->
                        Text("• ${f.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable {
                            HeartRateBle.stopScan(c); HeartRateBle.save(c, f); strap = f; HeartRateBle.connect(c); scanning = false; SyncWorker.schedule(c)
                        }.padding(vertical = 8.dp))
                    }
                }
            }
            if (HealthStore.healthConnectAvailable(c)) Section {
                Text("Sleep and watch data", style = MaterialTheme.typography.titleLarge)
                if (hcGranted) {
                    Text(lastSleep?.let { s -> "Last night: ${s.asleepMin / 60} h ${s.asleepMin % 60} min asleep" +
                        (if (s.deepMin + s.remMin > 0) " · deep ${s.deepMin} min · REM ${s.remMin} min" else "") }
                        ?: "Connected. Sleep appears here when your watch's app writes it to Health Connect.",
                        style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text("If your watch's app (Samsung Health, Fitbit, Zepp, Mi Fitness…) writes to Health Connect, Mettle can read sleep and resting heart rate.",
                        style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                    TextButton(onClick = { hcAsk.launch(HealthStore.permissions) }) { Text("Connect Health Connect", color = MettleColors.Orange) }
                }
            }
        }
    }
}

/** Fingertip heart-rate check: finger over the camera and flash for 30 seconds. */
@Composable
fun FingertipScreen(onDone: () -> Unit) {
    val c = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    var running by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(30) }
    var result by remember { mutableStateOf<app.mettle.health.PulseResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    val samples = remember { mutableStateListOf<Pair<Long, Double>>() }

    DisposableEffect(running) {
        if (!running) return@DisposableEffect onDispose { }
        val exec = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(c)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { img ->
                val y = img.planes[0]; val buf = y.buffer; val w = img.width; val h = img.height; val rs = y.rowStride
                var sum = 0L; var n = 0
                for (row in h / 3 until 2 * h / 3 step 4) for (col in w / 3 until 2 * w / 3 step 4) { sum += buf.get(row * rs + col).toInt() and 0xff; n++ }
                val v = if (n > 0) sum.toDouble() / n else 0.0
                val t = System.currentTimeMillis()
                androidx.core.content.ContextCompat.getMainExecutor(c).execute { samples += t to v }
                img.close()
            }
            runCatching {
                provider!!.unbindAll()
                val cam = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                cam.cameraControl.enableTorch(true)
            }
        }, ContextCompat.getMainExecutor(c))
        onDispose { runCatching { provider?.unbindAll() }; exec.shutdown() }
    }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        samples.clear(); result = null; failed = false
        for (s in 30 downTo 1) { secondsLeft = s; delay(1000) }
        running = false
        val r = HealthMath.pulse(samples.drop(60))     // skip the first 2 s while the finger settles
        if (r == null || r.quality < 0.25) failed = true else {
            result = r
            HealthStore.addSpotCheck(c, SpotCheck(System.currentTimeMillis(), r.bpm, r.quality, if (LocalTime.now().hour < 11) "morning" else "rest"))
            SyncWorker.schedule(c)
        }
    }

    Column(Modifier.fillMaxSize().background(MettleColors.Surface)) {
        TopBar("Fingertip heart rate")
        Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(when {
                result != null -> "Your heart rate"
                failed -> "Couldn't read a clear pulse. Cover the camera AND flash gently with your fingertip, keep still, and try again."
                running -> "Keep your fingertip still over the camera and flash… $secondsLeft s"
                else -> "Sit still, then gently cover the back camera and the flash with your fingertip. Don't press hard."
            }, style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)
            result?.let { Text("${it.bpm} bpm", style = MettleType.Hero, color = MettleColors.Orange) }
            if (running && samples.size > 30) {
                val tail = samples.takeLast(150)
                val lo = tail.minOf { it.second }; val hi = tail.maxOf { it.second }.coerceAtLeast(lo + 0.5)
                Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                    for (i in 1 until tail.size) drawLine(MettleColors.Orange,
                        Offset((i - 1) * size.width / tail.size, (size.height * (1 - (tail[i - 1].second - lo) / (hi - lo))).toFloat()),
                        Offset(i * size.width / tail.size, (size.height * (1 - (tail[i].second - lo) / (hi - lo))).toFloat()), 4f)
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            when {
                !camOk -> Pill("ALLOW CAMERA", MettleColors.Orange, MettleColors.Surface, Modifier.fillMaxWidth()) { ask.launch(Manifest.permission.CAMERA) }
                result != null -> Pill("DONE", MettleColors.Orange, MettleColors.Surface, Modifier.fillMaxWidth()) { onDone() }
                !running -> Pill("START", MettleColors.Orange, MettleColors.Surface, Modifier.fillMaxWidth()) { running = true }
            }
        }
    }
}
