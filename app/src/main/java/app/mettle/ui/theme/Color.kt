package app.mettle.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Mettle colour system. Each colour has exactly one meaning:
 *  - Orange: brand, primary actions, "you", climbing/up, your route.
 *  - Blue: descending/down and the live GPS dot only.
 *  - Gold: personal records and achievements.
 *  - Green: fresh, ahead, improvements.
 *  - Amber: warnings only (tired, worn shoes, slow alerts).
 *  - Ink/greys/white: everything else.
 */
object MettleColors {
    val Orange = Color(0xFFFC4C02)
    val OrangeDeep = Color(0xFFA33100)
    val OrangeTint = Color(0xFFFFE9DE)
    val OrangeWash = Color(0xFFFFF6F1)

    /** Pace scale for maps and charts, slow → fast. */
    val PaceScale = listOf(Color(0xFFFDC29C), Color(0xFFFD8A4B), Color(0xFFE04400), Color(0xFFA33100))

    val Ink = Color(0xFF16161A)
    val Ink2 = Color(0xFF3F3F46)
    val Sub = Color(0xFF5F5F68)
    val Muted = Color(0xFF8A8A93)
    val Line = Color(0xFFECECEF)
    val Band = Color(0xFFF2F2F4)
    val Surface = Color(0xFFFFFFFF)

    val Descent = Color(0xFF2563EB)
    val DescentTint = Color(0xFFEFF4FF)
    val Gold = Color(0xFFC99700)
    val GoldTint = Color(0xFFFFF1C7)
    val GoldText = Color(0xFF8A6100)
    val Good = Color(0xFF16A34A)
    val Warn = Color(0xFFD97706)
    val WarnText = Color(0xFFB45309)
}
