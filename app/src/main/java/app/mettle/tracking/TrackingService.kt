package app.mettle.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mettle.MainActivity
import app.mettle.MettleApplication
import app.mettle.R
import app.mettle.data.db.DayMinuteEntity
import app.mettle.data.db.DayState
import app.mettle.data.db.TrackPointEntity
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Live values shared between the service, receivers and the UI. */
object TrackingState {
    @Volatile var sensorState: DayState = DayState.UNKNOWN
    @Volatile var running: Boolean = false
    @Volatile var lastPressureHpa: Float? = null
}

/**
 * All-day tracking. Runs as a foreground service (Android requires the
 * visible notification) and keeps the CPU asleep as much as possible:
 * the step chip and barometer deliver batched readings, Android's activity
 * detector reports changes, and GPS frequency follows the current activity.
 */
class TrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private lateinit var sensors: SensorManager
    private var locationState: DayState? = null
    private val zone get() = ZoneId.systemDefault()
    private val db get() = (application as MettleApplication).database
    private val prefs get() = getSharedPreferences("tracking", MODE_PRIVATE)
    private val altBuffer = ArrayDeque<Pair<Long, Double>>()   // last few minutes of barometric height

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val points = result.locations.map { l ->
                TrackPointEntity(
                    tMs = l.time, lat = l.latitude, lon = l.longitude,
                    altitudeM = if (l.hasAltitude()) l.altitude else null,
                    accuracyM = if (l.hasAccuracy()) l.accuracy else null,
                    verticalAccuracyM = if (Build.VERSION.SDK_INT >= 26 && l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
                    speedMs = if (l.hasSpeed()) l.speed else null,
                    pressureHpa = TrackingState.lastPressureHpa,
                    state = TrackingState.sensorState,
                )
            }
            scope.launch { points.forEach { db.track().insert(it) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!TrackingState.running) {
            TrackingState.running = true
            recordGapSinceLastHeartbeat()
            prefs.edit().putLong(KEY_SERVICE_START, System.currentTimeMillis()).apply()
            registerSensors()
            registerActivityRecognition()
            updateLocationRequests(DayState.STILL)
            scope.launch { minuteTicker() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TrackingState.running = false
        sensors.unregisterListener(this)
        runCatching { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(locationCallback) }
        runCatching { ActivityRecognition.getClient(this).removeActivityUpdates(activityPendingIntent()) }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "All-day tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Mettle tracks your steps and activity"
                    setShowBadge(false)
                },
            )
        }
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Tracking your day"), type)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mettle)
            .setContentTitle("Mettle")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ---------------------------------------------------------------- sensors

    private fun registerSensors() {
        val fiveMinutesUs = 5 * 60 * 1_000_000
        sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, fiveMinutesUs)
        } ?: sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, fiveMinutesUs)
        }
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensors.registerListener(this, it, 1_000_000, fiveMinutesUs)   // 1 Hz, batched
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val wallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                TrackingState.lastPressureHpa = event.values[0]
                synchronized(altBuffer) {
                    altBuffer.addLast(wallMs to app.mettle.workout.WorkoutMath.pressureAltitudeM(event.values[0]))
                    while (altBuffer.isNotEmpty() && altBuffer.first().first < wallMs - 6 * 60_000) altBuffer.removeFirst()
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> addSteps(wallMs, 1)
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values[0].toLong()
                val last = prefs.getLong(KEY_LAST_COUNTER, -1)
                val delta = when {
                    last < 0 -> 0L              // first reading: just remember it
                    total < last -> total       // phone restarted, counter began again at 0
                    else -> total - last
                }
                prefs.edit().putLong(KEY_LAST_COUNTER, total).apply()
                if (delta in 1..2000) addSteps(wallMs, delta.toInt())
            }
        }
    }

    private fun addSteps(atMs: Long, steps: Int) {
        val t = Instant.ofEpochMilli(atMs).atZone(zone)
        val day = t.toLocalDate().toString()
        val minute = t.hour * 60 + t.minute
        scope.launch {
            writeLock.withLock {
                val existing = db.day().minute(day, minute)
                val total = (existing?.steps ?: 0) + steps
                db.day().upsert(DayMinuteEntity(day, minute, total, DayMath.resolve(TrackingState.sensorState, total), System.currentTimeMillis()))
            }
            val state = DayMath.resolve(TrackingState.sensorState, steps)
            if (state != locationState) updateLocationRequests(state)
            maybeAutoStartRun(day, minute)
        }
    }

    /** Running for 2 full minutes with no workout recording → start GPS recording automatically. */
    private suspend fun maybeAutoStartRun(day: String, minute: Int) {
        if (app.mettle.workout.WorkoutService.live.value.recording || app.mettle.workout.WorkoutService.unfinished(this) != null) return
        if (!getSharedPreferences("tracking", MODE_PRIVATE).getBoolean("auto_start_runs", true)) return
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val a = db.day().minute(day, minute - 1); val b = db.day().minute(day, minute - 2)
        if (a?.state == DayState.RUNNING && b?.state == DayState.RUNNING) {
            runCatching { app.mettle.workout.WorkoutService.start(this, app.mettle.data.db.ActivityType.RUN) }
        }
    }

    /** Writes a row for each passing minute so stillness shows in the timeline, and refreshes the notification. */
    private suspend fun minuteTicker() {
        while (scope.isActive) {
            val now = Instant.now().atZone(zone)
            val day = now.toLocalDate().toString()
            val minute = now.hour * 60 + now.minute
            val posture = if (TrackingState.sensorState in setOf(DayState.STILL, DayState.UNKNOWN)) probePosture() else null
            writeLock.withLock {
                val existing = db.day().minute(day, minute)
                if (existing == null) {
                    db.day().upsert(DayMinuteEntity(day, minute, 0, posture ?: DayMath.resolve(TrackingState.sensorState, 0), System.currentTimeMillis()))
                } else if (posture != null && existing.steps < 10) {
                    db.day().upsert(existing.copy(state = posture, updatedAt = System.currentTimeMillis()))
                }
                // Barometer phones: floors and lift rides in the minute that just ended.
                val prevT = now.minusMinutes(1)
                val prevDay = prevT.toLocalDate().toString(); val prevMin = prevT.hour * 60 + prevT.minute
                val startMs = prevT.withSecond(0).withNano(0).toInstant().toEpochMilli()
                val alt = synchronized(altBuffer) { ArrayList(altBuffer) }
                val dh = if (alt.size > 10) app.mettle.stairs.StairMath.heightChange(alt, startMs, startMs + 60_000) else null
                if (dh != null && kotlin.math.abs(dh) >= 1.8) {
                    val row = db.day().minute(prevDay, prevMin)
                    if (row != null && row.floorsUp == 0 && row.floorsDown == 0 && row.liftTrips == 0) {
                        val floors = kotlin.math.round(kotlin.math.abs(dh) / 3.0).toInt().coerceAtLeast(1)
                        db.day().upsert(when {
                            row.steps < 8 && kotlin.math.abs(dh) >= 4.5 -> row.copy(liftTrips = 1)
                            row.steps >= 8 && dh > 0 -> row.copy(floorsUp = floors)
                            row.steps >= 8 -> row.copy(floorsDown = floors)
                            else -> row
                        }.copy(updatedAt = System.currentTimeMillis()))
                    }
                }
            }
            prefs.edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
            if (minute % 5 == 0) {
                val steps = db.day().minutesNow(day).sumOf { it.steps }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(String.format(Locale.US, "Tracking your day · %,d steps", steps)))
            }
            delay(60_000L - System.currentTimeMillis() % 60_000L + 500)
        }
    }

    /**
     * Two seconds of accelerometer while still: phone tilt tells sitting from standing
     * (works best with the phone in a trouser pocket). Flat and motionless = on a table.
     */
    private suspend fun probePosture(): DayState? {
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        val xs = ArrayList<FloatArray>()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            override fun onSensorChanged(e: SensorEvent) { synchronized(xs) { xs += e.values.copyOf() } }
        }
        sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        delay(2000)
        sensors.unregisterListener(listener)
        val v = synchronized(xs) { ArrayList(xs) }
        if (v.size < 20) return null
        val mx = v.map { it[0] }.average(); val my = v.map { it[1] }.average(); val mz = v.map { it[2] }.average()
        val variance = v.map { (it[0] - mx).let { d -> d * d } + (it[1] - my).let { d -> d * d } + (it[2] - mz).let { d -> d * d } }.average()
        return when {
            variance < 0.0008 && kotlin.math.abs(mz) > 9.0 -> DayState.STILL          // lying on a table, not on the body
            kotlin.math.abs(my) > 7.0 -> DayState.STANDING                                // phone upright in a pocket
            else -> DayState.SITTING                                                      // thigh horizontal
        }
    }

    // ---------------------------------------------------------------- activity detector

    private fun activityPendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(this, 7, Intent(this, ActivityReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityRecognition() {
        if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACTIVITY_RECOGNITION)) return
        runCatching { ActivityRecognition.getClient(this).requestActivityUpdates(60_000L, activityPendingIntent()) }
    }

    // ---------------------------------------------------------------- location

    /** GPS effort follows activity: none when still, every minute walking, every 20 s running/driving. */
    @SuppressLint("MissingPermission")
    private fun updateLocationRequests(state: DayState) {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val bucket = when (state) {
            DayState.RUNNING, DayState.VEHICLE, DayState.CYCLING -> DayState.RUNNING
            DayState.WALKING -> DayState.WALKING
            else -> DayState.STILL
        }
        if (bucket == locationState) return
        locationState = bucket
        val request = when (bucket) {
            DayState.RUNNING -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 20_000L).setMinUpdateDistanceMeters(10f).build()
            DayState.WALKING -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).setMinUpdateDistanceMeters(15f).build()
            else -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15 * 60_000L)
                .setMinUpdateIntervalMillis(5 * 60_000L).setMinUpdateDistanceMeters(50f).build()
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        runCatching {
            client.removeLocationUpdates(locationCallback)
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    /** If tracking was off for more than 10 minutes, remember when and why (phone off vs app stopped). */
    private fun recordGapSinceLastHeartbeat() {
        val last = prefs.getLong(KEY_HEARTBEAT, 0L)
        val now = System.currentTimeMillis()
        if (last == 0L || now - last < 10 * 60_000L) return
        val bootAt = now - SystemClock.elapsedRealtime()
        val reason = if (bootAt > last) "phone_off" else "app_stopped"
        val pending = org.json.JSONArray(prefs.getString(KEY_GAPS, "[]"))
        pending.put(org.json.JSONObject().put("start", last).put("end", now).put("reason", reason))
        prefs.edit().putString(KEY_GAPS, pending.toString()).apply()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL = "tracking"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "app.mettle.STOP_TRACKING"
        private const val KEY_LAST_COUNTER = "last_step_counter"
        private const val KEY_SERVICE_START = "service_start"
        private const val KEY_HEARTBEAT = "heartbeat"
        const val KEY_GAPS = "pending_gaps"

        fun start(context: Context) {
            context.getSharedPreferences("tracking", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.getSharedPreferences("tracking", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun isEnabled(context: Context) = context.getSharedPreferences("tracking", MODE_PRIVATE).getBoolean("enabled", false)
    }
}

/** Receives Android's activity detection (still / walking / running / vehicle / cycling). */
class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val a = result.mostProbableActivity
        if (a.confidence < 50) return
        TrackingState.sensorState = when (a.type) {
            DetectedActivity.STILL -> DayState.STILL
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> DayState.WALKING
            DetectedActivity.RUNNING -> DayState.RUNNING
            DetectedActivity.IN_VEHICLE -> DayState.VEHICLE
            DetectedActivity.ON_BICYCLE -> DayState.CYCLING
            else -> TrackingState.sensorState
        }
    }
}

/** Restarts tracking after the phone reboots or Mettle updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (TrackingService.isEnabled(context)) runCatching { TrackingService.start(context) }
    }
}
