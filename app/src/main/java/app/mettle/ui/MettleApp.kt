package app.mettle.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val onRecord = current?.route == Tab.Record.route

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
            composable(Tab.Home.route) { HomeScreen(db, onRecord = { nav.navigate(Tab.Record.route) }, onProgress = { nav.navigate(Tab.Progress.route) }, onSetup = { nav.navigate("setup") }) }
            composable("setup") { app.mettle.ui.tracking.TrackingSetupScreen(onDone = { nav.popBackStack() }) }
            composable(Tab.Record.route) { RecordScreen(onClose = { nav.popBackStack() }) }
            composable(Tab.Progress.route) { ProgressScreen(db, profile) }
        }
    }
}
