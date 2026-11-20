package app.mettle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import app.mettle.ui.Root
import app.mettle.ui.theme.MettleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
        )
        super.onCreate(savedInstanceState)
        val db = (application as MettleApplication).database
        setContent { MettleTheme { Root(db) } }
    }

    override fun onStart() { super.onStart(); app.mettle.update.AppVisibility.foreground = true }
    override fun onStop() { app.mettle.update.AppVisibility.foreground = false; super.onStop() }
}
