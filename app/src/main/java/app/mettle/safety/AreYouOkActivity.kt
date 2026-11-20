package app.mettle.safety

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleTheme
import app.mettle.ui.theme.MettleType
import app.mettle.ui.workout.Pill

/** Full-screen "Are you OK?" countdown, shown over the lock screen. */
class AreYouOkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContent {
            MettleTheme {
                val cd by SafetyState.countdown.collectAsState()
                LaunchedEffect(cd) { if (cd == null) finish() }
                Column(Modifier.fillMaxSize().background(MettleColors.Surface).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (cd?.first == "fall") "Did you fall?" else "Are you OK?", style = MaterialTheme.typography.headlineLarge)
                    Text("${cd?.second ?: 0}", style = MettleType.Hero, color = MettleColors.Orange)
                    Text("Your emergency contacts and group admin will be alerted with your location when this reaches zero.",
                        style = MaterialTheme.typography.bodyLarge, color = MettleColors.Ink2)
                    Pill("I'M OK", MettleColors.Orange, MettleColors.Surface, Modifier.fillMaxWidth()) { SafetyState.cancel(this@AreYouOkActivity); finish() }
                }
            }
        }
    }
}
