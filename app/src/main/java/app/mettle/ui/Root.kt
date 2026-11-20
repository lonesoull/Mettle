package app.mettle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.mettle.data.db.MettleDatabase
import app.mettle.data.remote.AuthState
import app.mettle.data.remote.Backend
import app.mettle.data.remote.Profile
import app.mettle.ui.auth.ProfileSetupScreen
import app.mettle.ui.auth.SignInScreen
import app.mettle.ui.theme.MettleColors
import app.mettle.update.UpdateBanner
import androidx.compose.ui.platform.LocalContext
import app.mettle.sync.ProfileCache
import app.mettle.sync.SyncWorker
import app.mettle.tracking.TrackingService
import app.mettle.ui.tracking.setupStatus

/** Signed out → sign in; signed in without height → profile setup; otherwise the app. */
@Composable
fun Root(db: MettleDatabase) {
    val context = LocalContext.current
    val auth by Backend.authState.collectAsState(AuthState.Loading)
    var profile by remember { mutableStateOf<Profile?>(null) }
    var profileLoaded by remember { mutableStateOf(false) }
    var serverUnreachable by remember { mutableStateOf(false) }

    val signedIn = auth as? AuthState.SignedIn
    LaunchedEffect(signedIn?.userId) {
        profile = null
        profileLoaded = false
        if (signedIn != null) {
            // Show the saved copy immediately; refresh from the server in the background.
            val cached = ProfileCache.profile(context, signedIn.userId)
            if (cached != null) { profile = cached; profileLoaded = true }
            var remote: Profile? = null
            var reached = false
            repeat(3) { attempt ->
                if (!reached) {
                    runCatching { Backend.loadProfile(signedIn.userId) }
                        .onSuccess { remote = it; reached = true }
                        .onFailure { kotlinx.coroutines.delay(2000L * (attempt + 1)) }
                }
            }
            if (reached) {
                profile = remote
                remote?.let { ProfileCache.saveProfile(context, it) }
            }
            // Only if the server answered and there truly is no completed profile do we ask for one.
            profileLoaded = true
            serverUnreachable = !reached && cached == null
            SyncWorker.schedule(context)
            if (TrackingService.isEnabled(context) && setupStatus(context).essentials) runCatching { TrackingService.start(context) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        UpdateBanner()
        Box(Modifier.weight(1f)) {
            if (serverUnreachable && profile == null) Offline() else RootContent(db, auth, signedIn, profile, profileLoaded) { profile = it }
        }
    }
}

@Composable
private fun RootContent(
    db: MettleDatabase, auth: AuthState, signedIn: AuthState.SignedIn?, profile: Profile?, profileLoaded: Boolean,
    onProfile: (Profile) -> Unit,
) {
    val context = LocalContext.current
    when {
        auth is AuthState.SignedOut -> SignInScreen()
        signedIn == null || !profileLoaded -> Loading()
        profile?.isComplete != true -> ProfileSetupScreen(signedIn.userId, profile) { p ->
            ProfileCache.saveProfile(context, p); onProfile(p)
        }
        else -> MettleApp(db, profile, onSignOut = { })
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().background(MettleColors.Surface), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MettleColors.Orange)
    }
}

@Composable
private fun Offline() {
    Box(Modifier.fillMaxSize().background(MettleColors.Surface), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            "Can't reach the Mettle server. Check your internet connection and reopen the app.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2,
            modifier = androidx.compose.ui.Modifier.padding(32.dp),
        )
    }
}
