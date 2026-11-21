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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** Spacing scale used everywhere (dp). */
object Space { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp }

/** White full-width section separated from the next by the grey band, with an optional header. */
@Composable
fun Section(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(MettleColors.Surface).padding(horizontal = Space.l, vertical = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (action != null && onAction != null) {
                    androidx.compose.material3.TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(action, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = MettleColors.Orange)
                    }
                }
            }
        }
        content()
    }
}

/** Tappable settings/list row: icon, title, optional subtitle, and a chevron, value or custom trailing element. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: androidx.compose.ui.graphics.Color = MettleColors.Ink,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(iconTint.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = MettleColors.Ink)
            if (subtitle != null) Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
        }
        if (value != null) Text(value, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
        if (trailing != null) trailing()
        else if (onClick != null) androidx.compose.material3.Icon(app.mettle.ui.theme.MettleIcons.Chevron, contentDescription = null, tint = MettleColors.Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MettleColors.Orange, contentColor = MettleColors.Surface),
    ) { Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MettleColors.Ink),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MettleColors.Ink),
    ) { Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }
}

/** Friendly message for screens or sections with nothing to show yet. */
@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(body, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
        if (action != null && onAction != null) PrimaryButton(action, Modifier.padding(top = Space.s), onClick = onAction)
    }
}

/**
 * Top bar. Pass [onBack] on inner screens to get a back arrow (48 dp target, labelled for screen readers).
 */
@Composable
fun TopBar(title: String, wordmark: Boolean = false, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(MettleColors.Surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = if (onBack != null) 4.dp else Space.l, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    androidx.compose.material3.Icon(app.mettle.ui.theme.MettleIcons.Back, contentDescription = "Back", tint = MettleColors.Ink)
                }
            }
            if (wordmark) {
                Text(title, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 22.sp,
                    letterSpacing = (-0.6).sp, modifier = Modifier.weight(1f).semantics { heading() })
            } else {
                Text(title, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp).semantics { heading() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        androidx.compose.material3.HorizontalDivider(color = MettleColors.Line, thickness = 1.dp)
    }
}

/** Bigger stat for headline numbers. */
@Composable
fun StatBig(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, color: androidx.compose.ui.graphics.Color = MettleColors.Ink) {
    Column(modifier) {
        Text(label, style = MettleType.Label, color = MettleColors.Sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MettleType.StatMedium, color = color)
            if (unit != null) Text(" $unit", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.padding(bottom = 3.dp))
        }
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
