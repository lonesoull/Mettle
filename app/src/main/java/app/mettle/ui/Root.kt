package app.mettle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

/** Signed out → sign in; signed in without height → profile setup; otherwise the app. */
@Composable
fun Root(db: MettleDatabase) {
    val auth by Backend.authState.collectAsState(AuthState.Loading)
    var profile by remember { mutableStateOf<Profile?>(null) }
    var profileLoaded by remember { mutableStateOf(false) }

    val signedIn = auth as? AuthState.SignedIn
    LaunchedEffect(signedIn?.userId) {
        profile = null
        profileLoaded = false
        if (signedIn != null) {
            profile = runCatching { Backend.loadProfile(signedIn.userId) }.getOrNull()
            profileLoaded = true
        }
    }

    when {
        auth is AuthState.SignedOut -> SignInScreen()
        signedIn == null || !profileLoaded -> Loading()
        profile?.isComplete != true -> ProfileSetupScreen(signedIn.userId, profile) { profile = it }
        else -> MettleApp(db, profile!!, onSignOut = { })
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().background(MettleColors.Surface), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MettleColors.Orange)
    }
}
