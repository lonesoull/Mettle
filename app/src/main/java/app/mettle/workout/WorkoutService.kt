package app.mettle.workout

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mettle.MainActivity
import app.mettle.MettleApplication
import app.mettle.R
import app.mettle.data.db.ActivityType
import app.mettle.data.db.RecordingSampleEntity
import app.mettle.ui.Fmt
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** What the live recording screen shows. */
data class LiveWorkout(
    val sessionId: String? = null,
    val type: ActivityType = ActivityType.RUN,
    val startedAt: Long = 0L,
    val recording: Boolean = false,
    val paused: Boolean = false,
    val autoPaused: Boolean = false,
    val elapsedS: Int = 0,
    val movingS: Int = 0,
    val distanceM: Int = 0,
    val currentPaceSKm: Double? = null,
    val avgPaceSKm: Double? = null,
    val elevGainM: Double = 0.0,
    val route: List<Pair<Double, Double>> = emptyList(),
    val gpsAccuracyM: Float? = null,
    val gpsProblem: String? = null,      // why GPS isn't arriving (permission, location off)
    val lastFixAt: Long = 0L,
    // Stairs
    val machine: Boolean = false,
    val placeId: String? = null,
    val steps: Int = 0,
    val floorsUp: Int = 0,
    val floorsDown: Int = 0,
    val setNo: Int = 1,
    val climbing: Boolean? = null,
    val sets: List<app.mettle.stairs.StairSet> = emptyList(),
    val hasBarometer: Boolean = false,
    val heartRate: Int? = null,
)

/**
 * Records one workout: GPS every second, steps and barometer. Every reading is
 * written to the phone's database immediately, so nothing is lost if the phone
 * or app stops — the workout can be saved later.
 */
class WorkoutService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val samples = ArrayList<Sample>()
    private var firstSteps: Float? = null
    private var steps: Int? = null
    private var pressure: Float? = null
    private lateinit var sensors: SensorManager
    private var tts: android.speech.tts.TextToSpeech? = null
    private val stepTimes = ArrayList<Long>()
    private val altitude = ArrayList<Pair<Long, Double>>()
    private val laps = ArrayList<Long>()
    private var lastPressureRow = 0L
    private var stepsPerFloor = 22
    private var floorHeight = 3.0
    private var spokenKm = 0
    @Volatile private var segment = 0
    @Volatile private var mslBusy = false
    @Volatile private var fixes = 0L
    @Volatile private var lastFixMs = 0L
    private val db get() = (application as MettleApplication).database

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val live = state.value
            if (!live.recording) return
            for (l in result.locations) {
                // Sea-level height (not GPS ellipsoid height); the correction is prepared once per area off the main thread.
                if (!app.mettle.tracking.Msl.ready(l) && !mslBusy) {
                    mslBusy = true
                    val copy = android.location.Location(l)
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.mettle.tracking.Msl.prepare(this@WorkoutService, copy) }; mslBusy = false }
                }
                if (fixes++ == 0L) app.mettle.tracking.Diag.log(this@WorkoutService, "Workout: first GPS fix after ${(System.currentTimeMillis() - live.startedAt) / 1000} s, ±${l.accuracy.toInt()} m")
                lastFixMs = System.currentTimeMillis()
                val s = Sample(l.time, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null,
                    app.mettle.tracking.Msl.corrected(l), pressure, steps, if (l.hasSpeed()) l.speed else null, segment)
                synchronized(samples) { if (!live.paused) samples += s }
                scope.launch {
                    db.recording().insert(RecordingSampleEntity(
                        sessionId = live.sessionId!!, tMs = l.time, lat = l.latitude, lon = l.longitude,
                        accuracyM = s.accuracyM, altitudeM = s.altitudeM, speedMs = if (l.hasSpeed()) l.speed else null,
                        steps = steps, paused = live.paused, pressureHpa = pressure, heartRate = app.mettle.health.HeartRateBle.bpm.value,
                    ))
                }
                state.value = state.value.copy(gpsAccuracyM = s.accuracyM, gpsProblem = null, lastFixAt = System.currentTimeMillis())
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(ActivityType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "RUN"),
                intent.getBooleanExtra(EXTRA_MACHINE, false), intent.getStringExtra(EXTRA_PLACE))
            ACTION_LAP -> lap()
            ACTION_PAUSE -> { state.value = state.value.copy(paused = true); speak("Workout paused"); refreshNotification() }
            ACTION_RESUME -> { segment++; state.value = state.value.copy(paused = false); speak("Workout resumed"); refreshNotification() }
            ACTION_FINISH -> { finish(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun begin(type: ActivityType, machine: Boolean, placeId: String?) {
        if (state.value.recording) return
        val id = UUID.randomUUID().toString()
        val baro = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        state.value = LiveWorkout(sessionId = id, type = type, startedAt = System.currentTimeMillis(), recording = true,
            machine = machine, placeId = placeId, hasBarometer = baro)
        getSharedPreferences("workout", MODE_PRIVATE).edit().putString("session", id).putString("type", type.name)
            .putLong("started", state.value.startedAt).putBoolean("machine", machine).putString("place", placeId).apply()
        startInForeground()
        app.mettle.health.HeartRateBle.connect(this)
        synchronized(stepTimes) { stepTimes.clear(); altitude.clear(); laps.clear() }
        if (type == ActivityType.STAIRS) {
            scope.launch {
                val place = placeId?.let { db.places().get(it) }
                stepsPerFloor = place?.stepsPerFloor ?: if (machine) 16 else 22
                floorHeight = place?.floorHeightM ?: 3.0
            }
            (sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER))
                ?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } else {
            sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            requestLocation()
        }
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensors.registerListener(this, it, 500_000) }
        spokenKm = 0; segment = 0
        tts = android.speech.tts.TextToSpeech(this) { ok -> if (ok == android.speech.tts.TextToSpeech.SUCCESS) tts?.language = java.util.Locale.getDefault() }
        scope.launch { ticker() }
    }

    private fun finish() {
        state.value = state.value.copy(recording = false)
        tts?.shutdown(); tts = null
        app.mettle.health.HeartRateBle.disconnect()
        sensors.unregisterListener(this)
        runCatching { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(locationCallback) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Recomputes live stats every 2 seconds and refreshes the notification. */
    private suspend fun ticker() {
        var n = 0
        while (scope.isActive && state.value.recording) {
            val ok = runCatching { tick(n++) }
            if (ok.isFailure) app.mettle.tracking.Diag.log(this, "Workout update error: ${ok.exceptionOrNull()?.javaClass?.simpleName}")
            delay(2000)
        }
    }

    /** One live update (every 2 s). */
    private fun tick(n: Int) {
        run {
            state.value = state.value.copy(heartRate = app.mettle.health.HeartRateBle.bpm.value)
            if (state.value.type == ActivityType.STAIRS) {
                val (st, al, lp) = synchronized(stepTimes) { Triple(ArrayList(stepTimes), ArrayList(altitude), ArrayList(laps)) }
                val r = app.mettle.stairs.StairMath.analyse(st, al.takeIf { state.value.hasBarometer && it.size > 3 }, stepsPerFloor, floorHeight, lp, state.value.machine)
                val now = System.currentTimeMillis()
                val lastBout = r.bouts.lastOrNull()
                val active = lastBout != null && now - lastBout.endMs < 8_000
                state.value = state.value.copy(
                    elapsedS = ((now - state.value.startedAt) / 1000).toInt(), movingS = ((now - state.value.startedAt) / 1000).toInt(),
                    steps = st.size, floorsUp = r.floorsUp, floorsDown = r.floorsDown, sets = r.sets,
                    setNo = r.sets.size + (if (r.sets.lastOrNull()?.floorsDown ?: 1 > 0) 1 else 0).coerceAtLeast(1),
                    climbing = if (!active) null else lastBout!!.direction == app.mettle.stairs.Direction.UP || (lastBout.direction == app.mettle.stairs.Direction.FLAT && !state.value.hasBarometer),
                )
                if (n % 2 == 0) refreshNotification()
                return
            }
            val snapshot = synchronized(samples) { ArrayList(samples) }
            val r = WorkoutMath.analyse(snapshot)
            val now = System.currentTimeMillis()
            val recent = snapshot.filter { it.tMs >= now - 20_000 }
            val recentR = if (recent.size >= 3) WorkoutMath.analyse(recent) else null
            val autoPaused = r.moving.isNotEmpty() && !r.moving.last()
            state.value = state.value.copy(
                elapsedS = ((now - state.value.startedAt) / 1000).toInt(),
                movingS = r.movingS, distanceM = r.distanceM, avgPaceSKm = r.avgPaceSKm,
                currentPaceSKm = run {
                    val sp = snapshot.filter { it.tMs >= now - 10_000 && it.speedMs != null && (it.accuracyM ?: 99f) <= 20f }.mapNotNull { it.speedMs }
                    when {
                        autoPaused || state.value.paused -> null
                        sp.size >= 3 -> sp.average().takeIf { it >= WorkoutMath.MOVING_SPEED }?.let { 1000.0 / it }
                        else -> recentR?.takeIf { it.distanceM >= 15 }?.let { it.movingS / (it.distanceM / 1000.0) }
                    }
                },
                elevGainM = r.elevGainM, route = r.lat.indices.map { r.lat[it] to r.lon[it] }, autoPaused = autoPaused,
            )
            // Voice coach: announce every kilometre.
            val km = state.value.distanceM / 1000
            if (km > spokenKm && !state.value.paused) {
                spokenKm = km
                val split = r.splits.getOrNull(km - 1)?.movingS
                val pace = state.value.avgPaceSKm?.toInt()
                val text = "Distance $km kilometre${if (km > 1) "s" else ""}. Time ${spokenTime(state.value.movingS)}." +
                    (split?.let { " Split pace ${it / 60} minutes ${it % 60} seconds." } ?: "") +
                    (pace?.let { " Average pace ${it / 60} minutes ${it % 60} seconds per kilometre." } ?: "")
                speak(text)
            }
            if (n % 2 == 0) refreshNotification()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        fixes = 0; lastFixMs = 0
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            state.value = state.value.copy(gpsProblem = "Precise location is off for Mettle")
            app.mettle.tracking.Diag.log(this, "Workout: no precise-location permission")
            return
        }
        val lm = getSystemService(android.location.LocationManager::class.java)
        if (!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            state.value = state.value.copy(gpsProblem = "Location is turned off on this phone")
            app.mettle.tracking.Diag.log(this, "Workout: phone location is off")
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(false).build()
        runCatching { LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(req, locationCallback, Looper.getMainLooper()) }
            .onFailure { state.value = state.value.copy(gpsProblem = "Couldn't start GPS"); app.mettle.tracking.Diag.log(this, "Workout: GPS request failed ${it.javaClass.simpleName}") }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        val live = state.value
        val now = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                pressure = event.values[0]
                if (live.type == ActivityType.STAIRS && live.recording && now - lastPressureRow >= 1000) {
                    lastPressureRow = now
                    synchronized(stepTimes) { altitude += now to WorkoutMath.pressureAltitudeM(event.values[0]) }
                    stairRow(now)
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> if (live.type == ActivityType.STAIRS && live.recording && !live.paused) {
                synchronized(stepTimes) { stepTimes += now }
                steps = stepTimes.size
                stairRow(now)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                if (firstSteps == null) firstSteps = event.values[0]
                val prev = steps ?: 0
                steps = (event.values[0] - firstSteps!!).toInt()
                // Stair phones without a step detector: spread the counted steps over now.
                if (live.type == ActivityType.STAIRS && live.recording && !live.paused) {
                    synchronized(stepTimes) { repeat((steps!! - prev).coerceIn(0, 50)) { stepTimes += now } }
                    stairRow(now)
                }
            }
        }
    }

    private fun stairRow(t: Long, lap: Boolean = false) {
        val live = state.value
        scope.launch {
            db.recording().insert(RecordingSampleEntity(sessionId = live.sessionId!!, tMs = t, lat = null, lon = null, accuracyM = null,
                altitudeM = null, speedMs = null, steps = steps, paused = live.paused, pressureHpa = pressure, lap = lap,
                heartRate = app.mettle.health.HeartRateBle.bpm.value))
        }
    }

    private fun lap() {
        val now = System.currentTimeMillis()
        synchronized(stepTimes) { laps += now }
        stairRow(now, lap = true)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout recording", NotificationManager.IMPORTANCE_LOW))
        }
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Recording started"), type)
    }

    private fun action(act: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, WorkoutService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Lock-screen notification with live stats and Pause / Resume / Finish buttons. */
    private fun notification(text: String): Notification {
        val live = state.value
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mettle)
            .setContentTitle(when { live.paused -> "Paused"; live.autoPaused -> "Auto-paused"; else -> "Recording " + live.type.name.lowercase() })
            .setContentText(text)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setUsesChronometer(!live.paused && live.startedAt > 0).setWhen(System.currentTimeMillis() - live.movingS * 1000L)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (live.recording) {
            if (live.paused) b.addAction(0, "Resume", action(ACTION_RESUME, 11)) else b.addAction(0, "Pause", action(ACTION_PAUSE, 12))
            if (live.type == ActivityType.STAIRS) b.addAction(0, "New set", action(ACTION_LAP, 13))
        }
        return b.build()
    }

    private fun refreshNotification() {
        val l = state.value
        val text = if (l.type == ActivityType.STAIRS) "▲ ${l.floorsUp} floors · ▼ ${l.floorsDown}"
            else "${Fmt.km2(l.distanceM.toLong())} km · ${Fmt.pace(l.currentPaceSKm ?: l.avgPaceSKm)} /km" + (l.heartRate?.let { " · $it bpm" } ?: "")
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun speak(text: String) { if (VoicePrefs.enabled(this)) tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, text.hashCode().toString()) }

    private fun spokenTime(s: Int): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return (if (h > 0) "$h hour${if (h > 1) "s" else ""} " else "") + "$m minute${if (m != 1) "s" else ""} $sec second${if (sec != 1) "s" else ""}"
    }

    companion object {
        const val CHANNEL = "workout"
        const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "start"; private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"; private const val ACTION_FINISH = "finish"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_MACHINE = "machine"
        private const val EXTRA_PLACE = "place"
        private const val ACTION_LAP = "lap"

        val state = MutableStateFlow(LiveWorkout())
        val live: StateFlow<LiveWorkout> get() = state

        private fun send(c: Context, action: String, type: ActivityType? = null, machine: Boolean = false, placeId: String? = null) {
            val i = Intent(c, WorkoutService::class.java).setAction(action)
            type?.let { i.putExtra(EXTRA_TYPE, it.name) }
            i.putExtra(EXTRA_MACHINE, machine); placeId?.let { i.putExtra(EXTRA_PLACE, it) }
            if (action == ACTION_START) ContextCompat.startForegroundService(c, i) else c.startService(i)
        }
        fun start(c: Context, type: ActivityType, machine: Boolean = false, placeId: String? = null) = send(c, ACTION_START, type, machine, placeId)
        fun newSet(c: Context) = send(c, ACTION_LAP)
        fun pause(c: Context) = send(c, ACTION_PAUSE)
        fun resume(c: Context) = send(c, ACTION_RESUME)
        fun finish(c: Context) = send(c, ACTION_FINISH)

        /** A recording that was interrupted (phone restarted, app killed) and not saved yet. */
        fun unfinished(c: Context): Triple<String, ActivityType, Long>? {
            if (state.value.recording) return null
            val p = c.getSharedPreferences("workout", MODE_PRIVATE)
            val id = p.getString("session", null) ?: return null
            return Triple(id, ActivityType.valueOf(p.getString("type", "RUN")!!), p.getLong("started", 0))
        }
        fun clearSession(c: Context) = c.getSharedPreferences("workout", MODE_PRIVATE).edit().clear().apply()
    }
}

/** Voice coach on/off (spoken km splits and pause/resume). */
object VoicePrefs {
    fun enabled(c: Context) = c.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).getBoolean("voice", true)
    fun set(c: Context, on: Boolean) = c.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit().putBoolean("voice", on).apply()
    fun keepScreenOn(c: Context) = c.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).getBoolean("screen_on", false)
    fun setKeepScreenOn(c: Context, on: Boolean) = c.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit().putBoolean("screen_on", on).apply()
}
