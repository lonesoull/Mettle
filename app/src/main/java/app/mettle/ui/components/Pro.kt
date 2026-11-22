package app.mettle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mettle.ui.theme.Inter
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.Numerals

/**
 * Mettle's professional surface language: white cards on a light grey page, hairline borders,
 * no shadows; orange reserved for the one main action and "your" highlights.
 */
object Ui {
    val Page = Color(0xFFF4F4F6)
    val Hairline = Color(0xFFE6E6EA)
    val CardShape = RoundedCornerShape(16.dp)
    val Gutter = 16.dp

    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)
    val Value = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 26.sp, fontFeatureSettings = "tnum")
    val ValueSmall = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum")
    val CardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)
    val PageTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val Strong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
}

/** A white card with a hairline border; tappable when [onClick] is set. */
@Composable
fun ProCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null,
            padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = Ui.Gutter).fillMaxWidth().clip(Ui.CardShape).background(MettleColors.Surface)
            .border(1.dp, Ui.Hairline, Ui.CardShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .then(if (padding) Modifier.padding(16.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Card heading: title on the left, an optional chevron when the card opens something. */
@Composable
fun CardHeader(title: String, opens: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Ui.CardTitle, color = MettleColors.Ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
        if (opens) Icon(MettleIcons.Chevron, contentDescription = null, tint = MettleColors.Muted, modifier = Modifier.size(20.dp))
    }
}

/** Section heading on the grey page, between cards. */
@Composable
fun PageSection(title: String) {
    Text(title, style = Ui.CardTitle, color = MettleColors.Ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp).semantics { heading() })
}

/** A number with its caption above and unit after, e.g. Distance / 6.47 km. */
@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, small: Boolean = false,
           color: Color = MettleColors.Ink) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Ui.Caption, color = MettleColors.Sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = if (small) Ui.ValueSmall else Ui.Value, color = color)
            unit?.let { Text(" $it", style = Ui.Caption, color = MettleColors.Sub, modifier = Modifier.padding(bottom = 3.dp)) }
        }
    }
}

/** A metric row with a leading icon, for compact summaries. */
@Composable
fun IconMetric(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Ui.Page), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MettleColors.Ink2, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(label, style = Ui.Caption, color = MettleColors.Sub)
            Text(value, style = Ui.Strong, color = MettleColors.Ink)
        }
    }
}

/** Initial-letter avatar. */
@Composable
fun Avatar(name: String, size: Int = 40, you: Boolean = false) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(if (you) MettleColors.OrangeTint else Ui.Page), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" },
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = (size * 0.42).sp),
            color = if (you) MettleColors.OrangeDeep else MettleColors.Ink2)
    }
}
