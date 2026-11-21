package app.mettle.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.mettle.data.db.ActivityType
import app.mettle.data.db.MettleDatabase
import app.mettle.data.remote.Profile
import app.mettle.space.KnownPlace
import app.mettle.space.Places
import app.mettle.ui.home.HomeScreen
import app.mettle.ui.progress.ProgressScreen
import app.mettle.ui.record.RecordScreen
import app.mettle.ui.space.DayReplayScreen
import app.mettle.ui.space.ReplayPoint
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.you.YouScreen
import app.mettle.workout.WorkoutService
import java.time.LocalDate
import java.time.ZoneId

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", MettleIcons.Home),
    Tab("day", "Day", MettleIcons.Day),
    Tab("record", "Record", MettleIcons.Record),
    Tab("progress", "Progress", MettleIcons.Progress),
    Tab("you", "You", MettleIcons.You),
)

/** Loads one local day of positions for the Day tab. */
suspend fun localDay(db: MettleDatabase, date: LocalDate): List<ReplayPoint> {
    val z = ZoneId.systemDefault()
    return db.track().between(date.atStartOfDay(z).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli())
        .map { ReplayPoint(it.tMs, it.lat, it.lon, it.altitudeM, it.accuracyM, it.state.name.lowercase()) }
}

@Composable
fun MettleApp(db: MettleDatabase, profile: Profile, onSignOut: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in setOf("home", "day", "progress", "you")
    val ctx = LocalContext.current

    Scaffold(
        containerColor = MettleColors.Band,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (showBar) BottomBar(nav, route) },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            // ---- Tabs
            composable("home") {
                HomeScreen(db,
                    onRecord = { nav.navigate("record") },
                    onSetup = { nav.navigate("setup") },
                    onDay = { nav.switchTab("day") },
                    onSafety = { nav.navigate("safety") },
                    onHealth = { nav.navigate("health") },
                    onOpen = { id -> nav.navigate("activity/$id") },
                    onResumeSave = { nav.navigate("save") },
                    onLiveWorkout = { nav.navigate("recording") })
            }
            composable("day") { DayReplayScreen("Your day", Places.known(ctx), db = db, load = { d -> localDay(db, d) }, onBack = null) }
            composable("progress") { ProgressScreen(db) }
            composable("you") {
                YouScreen(profile,
                    onEditProfile = { nav.navigate("editprofile") }, onSafety = { nav.navigate("safety") }, onHealth = { nav.navigate("health") },
                    onTracking = { nav.navigate("setup") }, onTransfer = { nav.navigate("transfer") }, onSensorLab = { nav.navigate("sensorlab") },
                    onAdmin = { nav.navigate("admin") }, onSignedOut = onSignOut)
            }

            // ---- Recording flow (full screen, no tab bar)
            composable("record") {
                RecordScreen(db, onClose = { nav.popBackStack() }, onStarted = { nav.navigate("recording") { popUpTo("home") } },
                    onCalibrate = { id -> nav.navigate("calibrate/$id") })
            }
            composable("recording") {
                val live by WorkoutService.live.collectAsState()
                if (live.type == ActivityType.STAIRS) app.mettle.ui.stairs.StairRecordingScreen(onFinished = { nav.navigate("save") { popUpTo("home") } })
                else app.mettle.ui.workout.RecordingScreen(onFinished = { nav.navigate("save") { popUpTo("home") } })
            }
            composable("save") {
                val pending = remember { WorkoutService.unfinished(ctx) }
                if (pending == null) LaunchedEffect(Unit) { nav.popBackStack() }
                else app.mettle.ui.workout.SaveWorkoutScreen(pending.first, pending.second, pending.third,
                    onSaved = { id -> nav.navigate("activity/$id") { popUpTo("home") } },
                    onDiscarded = { nav.popBackStack("home", false) })
            }
            composable("calibrate/{id}") { e -> app.mettle.ui.stairs.CalibrationScreen(db, e.arguments?.getString("id") ?: "", onDone = { nav.popBackStack() }) }

            // ---- Inner screens (back arrow)
            composable("activity/{id}") { e -> app.mettle.ui.workout.ActivityDetailScreen(db, e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() }) }
            composable("setup") { app.mettle.ui.tracking.TrackingSetupScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
            composable("safety") { app.mettle.ui.safety.SafetyScreen(onBack = { nav.popBackStack() }) }
            composable("health") { app.mettle.ui.health.HealthScreen(onFingertip = { nav.navigate("fingertip") }, onBack = { nav.popBackStack() }) }
            composable("fingertip") { app.mettle.ui.health.FingertipScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
            composable("transfer") { app.mettle.ui.transfer.TransferScreen(onBack = { nav.popBackStack() }) }
            composable("sensorlab") { app.mettle.ui.lab.SensorLabScreen(onBack = { nav.popBackStack() }) }
            composable("editprofile") {
                app.mettle.ui.auth.ProfileSetupScreen(profile.id, profile, onDone = { p -> app.mettle.sync.ProfileCache.saveProfile(ctx, p); nav.popBackStack() },
                    onBack = { nav.popBackStack() })
            }
            composable("admin") {
                app.mettle.ui.admin.AdminScreen(onMember = { id, name -> nav.navigate("member/$id/${Uri.encode(name)}") }, onBack = { nav.popBackStack() })
            }
            composable("member/{id}/{name}") { e ->
                val id = e.arguments?.getString("id") ?: ""; val name = e.arguments?.getString("name") ?: ""
                app.mettle.ui.admin.MemberScreen(id, name, onReplay = { nav.navigate("memberreplay/$id/${Uri.encode(name)}") }, onBack = { nav.popBackStack() })
            }
            composable("memberreplay/{id}/{name}") { e ->
                val id = e.arguments?.getString("id") ?: ""; val name = e.arguments?.getString("name") ?: ""
                val places by produceState(emptyList<KnownPlace>(), id) { value = app.mettle.ui.admin.loadMemberPlaces(id) }
                DayReplayScreen("$name's day", places, db = null, load = { d -> app.mettle.ui.admin.loadMemberDay(id, d) }, onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    Column(Modifier.fillMaxWidth().background(MettleColors.Surface).navigationBarsPadding()) {
        HorizontalDivider(color = MettleColors.Line)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                val selected = current == t.route
                if (t.route == "record") {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(52.dp).shadow(6.dp, CircleShape, spotColor = MettleColors.Orange).clip(CircleShape).background(MettleColors.Orange)
                            .clickable(role = Role.Button) { nav.navigate("record") }.semantics { contentDescription = "Record a workout" },
                            contentAlignment = Alignment.Center) {
                            Icon(MettleIcons.Record, contentDescription = null, tint = MettleColors.Surface, modifier = Modifier.size(26.dp))
                        }
                    }
                } else {
                    Column(
                        Modifier.weight(1f).height(64.dp).clickable(role = Role.Tab) { nav.switchTab(t.route) }.semantics { this.selected = selected },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(t.icon, contentDescription = null, tint = if (selected) MettleColors.Orange else MettleColors.Sub, modifier = Modifier.size(24.dp))
                        Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (selected) MettleColors.Orange else MettleColors.Sub,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}
