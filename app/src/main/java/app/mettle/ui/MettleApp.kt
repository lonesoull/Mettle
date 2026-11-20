package app.mettle.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.mettle.data.db.MettleDatabase
import app.mettle.data.remote.Profile
import app.mettle.ui.home.HomeScreen
import app.mettle.ui.progress.ProgressScreen
import app.mettle.ui.record.RecordScreen
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", MettleIcons.Home),
    Record("record", "Record", MettleIcons.Record),
    Progress("progress", "Progress", MettleIcons.Progress),
}

@Composable
fun MettleApp(db: MettleDatabase, profile: Profile, onSignOut: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val onRecord = current?.route in setOf(Tab.Record.route, "recording", "save", "setup", "calibrate/{id}", "activity/{id}", "sensorlab", "admin", "replay") || current?.route?.startsWith("activity") == true

    Scaffold(
        containerColor = MettleColors.Band,
        // Screens draw their own status-bar spacing (TopBar); only the bottom bar is inset here.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!onRecord) {
                NavigationBar(containerColor = MettleColors.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    Tab.entries.forEach { tab ->
                        val selected = current?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MettleColors.Orange,
                                selectedTextColor = MettleColors.Orange,
                                indicatorColor = MettleColors.OrangeTint,
                                unselectedIconColor = MettleColors.Ink2,
                                unselectedTextColor = MettleColors.Ink2,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Home.route) {
                HomeScreen(db, onRecord = { nav.navigate(Tab.Record.route) }, onProgress = { nav.navigate(Tab.Progress.route) },
                    onSetup = { nav.navigate("setup") }, onReplay = { nav.navigate("replay") }, onOpen = { id -> nav.navigate("activity/$id") }, onResumeSave = { nav.navigate("save") })
            }
            composable("recording") {
                val live by app.mettle.workout.WorkoutService.live.collectAsState()
                if (live.type == app.mettle.data.db.ActivityType.STAIRS) app.mettle.ui.stairs.StairRecordingScreen(onFinished = { nav.navigate("save") { popUpTo(Tab.Home.route) } })
                else app.mettle.ui.workout.RecordingScreen(onFinished = { nav.navigate("save") { popUpTo(Tab.Home.route) } })
            }
            composable("save") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val pending = remember { app.mettle.workout.WorkoutService.unfinished(ctx) }
                if (pending == null) { androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() } }
                else app.mettle.ui.workout.SaveWorkoutScreen(pending.first, pending.second, pending.third,
                    onSaved = { id -> nav.navigate("activity/$id") { popUpTo(Tab.Home.route) } },
                    onDiscarded = { nav.popBackStack(Tab.Home.route, false) })
            }
            composable("activity/{id}") { entry ->
                app.mettle.ui.workout.ActivityDetailScreen(db, entry.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
            }
            composable("setup") { app.mettle.ui.tracking.TrackingSetupScreen(onDone = { nav.popBackStack() }) }
            composable(Tab.Record.route) {
                RecordScreen(db, onClose = { nav.popBackStack() }, onStarted = { nav.navigate("recording") { popUpTo(Tab.Home.route) } },
                    onCalibrate = { id -> nav.navigate("calibrate/$id") })
            }
            composable("calibrate/{id}") { e -> app.mettle.ui.stairs.CalibrationScreen(db, e.arguments?.getString("id") ?: "", onDone = { nav.popBackStack() }) }
            composable(Tab.Progress.route) { ProgressScreen(db, profile, onSensorLab = { nav.navigate("sensorlab") }, onAdmin = { nav.navigate("admin") }) }
            composable("replay") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                app.mettle.ui.space.DayReplayScreen("Your day", app.mettle.space.Places.known(ctx), load = { date ->
                    val z = java.time.ZoneId.systemDefault()
                    db.track().between(date.atStartOfDay(z).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli())
                        .map { app.mettle.ui.space.ReplayPoint(it.tMs, it.lat, it.lon, it.altitudeM, it.accuracyM, it.state.name.lowercase()) }
                }, onBack = { nav.popBackStack() })
            }
            composable("admin") {
                androidx.compose.foundation.layout.Box(Modifier.padding(32.dp)) {
                    Text("The admin dashboard (live group map) arrives in the next update.")
                }
            }
            composable("sensorlab") { app.mettle.ui.lab.SensorLabScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
