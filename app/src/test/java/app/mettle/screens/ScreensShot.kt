package app.mettle.screens

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.mettle.data.db.ActivityEntity
import app.mettle.data.db.ActivitySub
import app.mettle.data.db.ActivityType
import app.mettle.data.db.BestEffortEntity
import app.mettle.data.db.DayMinuteEntity
import app.mettle.data.db.DayState
import app.mettle.data.db.LapEntity
import app.mettle.data.db.LapKind
import app.mettle.data.db.MettleDatabase
import app.mettle.data.db.TrackPointEntity
import app.mettle.data.remote.Profile
import app.mettle.sync.ProfileCache
import app.mettle.ui.theme.MettleTheme
import app.mettle.ui.workout.MapsConfig
import app.mettle.workout.Polyline
import app.mettle.workout.Streams
import app.mettle.workout.WorkoutSaver
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/** Renders every main screen with realistic sample data to build/shots/ for design review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1800dp-xxhdpi")
class ScreensShot {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: MettleDatabase
    private val profile = Profile("me", "Avinash", "admin", 175, 68.0, 9000)

    @Before fun seed() {
        MapsConfig.enabled = false
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION)
        app.getSharedPreferences("tracking", 0).edit().putBoolean("enabled", true).apply()
        ProfileCache.saveProfile(app, profile)
        db = Room.inMemoryDatabaseBuilder(app, MettleDatabase::class.java).allowMainThreadQueries().build()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowMin = LocalTime.now(zone).let { it.hour * 60 + it.minute }.coerceAtLeast(18 * 60)
        runBlocking {
            for (d in 0..20) {
                val day = today.minusDays(d.toLong())
                for (m in 0 until if (d == 0) nowMin else 1440) {
                    val (st, steps) = when (m) {
                        in 0 until 420 -> DayState.STILL to 0
                        in 420 until 440 -> DayState.WALKING to 95
                        in 440 until 480 -> DayState.RUNNING to 165
                        in 540 until 600 -> DayState.VEHICLE to 0
                        in 600 until 780 -> DayState.SITTING to (if (m % 30 == 0) 40 else 0)
                        in 780 until 800 -> DayState.WALKING to 100
                        in 800 until 1020 -> DayState.SITTING to 0
                        in 1020 until 1040 -> DayState.STANDING to 10
                        in 1080 until 1110 -> DayState.WALKING to 105
                        else -> DayState.STILL to 0
                    }
                    db.day().upsert(DayMinuteEntity(day.toString(), m, steps + d, st, 0, floorsUp = if (m in 780..782) 2 else 0, floorsDown = if (m == 800) 3 else 0))
                }
            }
            // A run around a lake (2 laps) and a stair session.
            val loop = (0..160).map { i -> val a = i / 160.0 * 2 * Math.PI; 12.9815 + 0.006 * sin(a) to 77.6195 + 0.004 * cos(a) }
            val start = today.atTime(6, 42).atZone(zone).toInstant().toEpochMilli()
            db.activities().upsert(ActivityEntity("run1", null, ActivityType.RUN, ActivitySub.OUTDOOR, "Morning Run", "Two laps before work.",
                start, zone.id, 2102, 2000, 6470, 14.0, 13.0, avgPaceSKm = 309.0, gapSKm = 301.0, avgCadenceSpm = 174, calories = 468, rpe = 6,
                trainingLoad = 200, routePolyline = Polyline.encode(loop), createdAt = start, updatedAt = start))
            // Per-second streams for the analysis charts (pace varies, a hill, heart rate).
            run {
                val n = 2000; val t = (0..n).toList()
                val dist = t.map { it * 3.2 + 40 * sin(it / 90.0) }.let { d -> d.map { it.coerceAtLeast(0.0) } }
                val st = Streams(2, t, t.map { loop[(it * 160 / n)].first }, t.map { loop[(it * 160 / n)].second }, dist,
                    t.map { 905.0 + 14 * sin(it / 300.0) }, t.map { true }, t.map { 132 + (it / 40) % 25 })
                java.util.zip.GZIPOutputStream(WorkoutSaver.streamsFile(app, "run1").outputStream()).bufferedWriter().use {
                    it.write(kotlinx.serialization.json.Json.encodeToString(Streams.serializer(), st))
                }
            }
            db.laps().upsertAll((1..6).map { LapEntity("l$it", "run1", it, LapKind.AUTO_KM, 0, 0, 1000, 321 - it * 4, (it % 3 - 1) * 3.0) })
            db.bestEfforts().upsertAll(listOf(BestEffortEntity("e1", "run1", "run_5k", 1531, 120, 1651, 1, start), BestEffortEntity("e2", "run1", "run_1k", 292, 1500, 1792, null, start)))
            val s2 = today.minusDays(1).atTime(19, 10).atZone(zone).toInstant().toEpochMilli()
            db.activities().upsert(ActivityEntity("st1", null, ActivityType.STAIRS, ActivitySub.STAIRWELL, "Stairwell Intervals", null, s2, zone.id,
                2780, 2200, 0, 417.0, 417.0, floorsUp = 139, floorsDown = 139, rpe = 8, trainingLoad = 370, createdAt = s2, updatedAt = s2))
            db.laps().upsertAll((1..10).map { LapEntity("s$it", "st1", it, LapKind.STAIR_SET, 0, 0, floorsUp = 14, floorsDown = 14, climbS = 160 + it * 5, descentS = 92 + it, restS = 60) })
            for (i in 0..300) {
                val a = i / 300.0 * 2 * Math.PI
                db.track().insert(TrackPointEntity(tMs = today.atStartOfDay(zone).toInstant().toEpochMilli() + i * 4 * 60_000L,
                    lat = 12.9815 + 0.01 * sin(a), lon = 77.6195 + 0.012 * cos(a), altitudeM = 905.0, accuracyM = 10f, verticalAccuracyM = null,
                    speedMs = null, pressureHpa = null, state = DayState.WALKING))
            }
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { MettleTheme { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test fun home() = shot("1-home") { app.mettle.ui.home.HomeScreen(db, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun day() = shot("2-day") { app.mettle.ui.space.DayReplayScreen("Your day", emptyList(), db, load = { d -> app.mettle.ui.localDay(db, d) }, onBack = null) }
    @Test fun progress() = shot("3-progress") { app.mettle.ui.progress.ProgressScreen(db) }
    @Test fun you() = shot("4-you") { app.mettle.ui.you.YouScreen(profile, {}, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun activity() = shot("5-activity") { app.mettle.ui.workout.ActivityDetailScreen(db, "run1", onBack = {}) }
    @Test fun stairsActivity() = shot("6-stairs") { app.mettle.ui.workout.ActivityDetailScreen(db, "st1", onBack = {}) }
    @Test fun record() = shot("7-record") { app.mettle.ui.record.RecordScreen(db, {}, {}, {}) }
    @Test fun safety() = shot("8-safety") { app.mettle.ui.safety.SafetyScreen(onBack = {}) }
    @Test fun health() = shot("9-health") { app.mettle.ui.health.HealthScreen(onFingertip = {}, onBack = {}) }
    private fun liveRun(paused: Boolean = false) = app.mettle.workout.LiveWorkout(sessionId = "x", recording = true, paused = paused, startedAt = 1,
        elapsedS = 1530, movingS = 1490, distanceM = 4870, currentPaceSKm = 302.0, avgPaceSKm = 306.0, elevGainM = 23.0, gpsAccuracyM = 4f,
        route = (0..120).map { i -> val a = i / 160.0 * 2 * Math.PI; 12.9815 + 0.006 * sin(a) to 77.6195 + 0.004 * cos(a) })
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun recordingMap() { app.mettle.workout.WorkoutService.state.value = liveRun(); shot("11-recording-map") { app.mettle.ui.workout.RecordingScreen {} } }
    @Config(qualifiers = "w411dp-h891dp-xxhdpi") @Test fun recordingPaused() { app.mettle.workout.WorkoutService.state.value = liveRun(paused = true); shot("12-recording-paused") { app.mettle.ui.workout.RecordingScreen {} } }
    @Test fun setup() = shot("10-setup") { app.mettle.ui.tracking.TrackingSetupScreen(onDone = {}) }
}
