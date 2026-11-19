package app.mettle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mettle.ui.theme.Inter
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType

/** White full-width section separated from the next by the grey band. */
@Composable
fun Section(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(MettleColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun TopBar(title: String, wordmark: Boolean = false, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(MettleColors.Surface).statusBarsPadding().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (wordmark) {
            Text(title, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 22.sp, letterSpacing = (-0.6).sp)
        } else {
            Text(title, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }
        actions()
    }
}

/** Label above a number, e.g. "Distance / 6.47 km". */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null) {
    Column(modifier) {
        Text(label, style = MettleType.Label, color = MettleColors.Sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MettleType.StatSmall, color = MettleColors.Ink)
            if (unit != null) Text(" $unit", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}
