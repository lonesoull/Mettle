package app.mettle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.mettle.R

/** Inter for all UI text. Bundled in the app, so it works offline. */
val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
    Font(R.font.inter_800, FontWeight.ExtraBold),
)

/** Condensed sports numerals for big stats (timers, distances, paces). */
val Numerals = FontFamily(
    Font(R.font.barlow_sc_600, FontWeight.SemiBold),
    Font(R.font.barlow_sc_700, FontWeight.Bold),
)

/** Fixed-width digits so live numbers never jitter. */
private const val TNUM = "tnum"

object MettleType {
    val Hero = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 76.sp, lineHeight = 72.sp, fontFeatureSettings = TNUM)
    val StatLarge = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp, fontFeatureSettings = TNUM)
    val StatMedium = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 28.sp, fontFeatureSettings = TNUM)
    val StatSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, fontFeatureSettings = TNUM)
    val Label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val Overline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp)
}

val MettleTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp),
)
