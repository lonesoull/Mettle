package app.mettle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = MettleColors.Orange,
    onPrimary = MettleColors.Surface,
    primaryContainer = MettleColors.OrangeTint,
    onPrimaryContainer = MettleColors.OrangeDeep,
    secondary = MettleColors.Ink,
    onSecondary = MettleColors.Surface,
    tertiary = MettleColors.Descent,
    background = MettleColors.Band,
    onBackground = MettleColors.Ink,
    surface = MettleColors.Surface,
    onSurface = MettleColors.Ink,
    surfaceVariant = MettleColors.Band,
    onSurfaceVariant = MettleColors.Sub,
    outline = MettleColors.Line,
    outlineVariant = MettleColors.Line,
    error = MettleColors.Warn,
)

@Composable
fun MettleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightScheme, typography = MettleTypography, content = content)
}
