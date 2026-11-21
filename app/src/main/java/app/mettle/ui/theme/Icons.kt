package app.mettle.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Mettle's own line icons (24×24, 2px strokes), same set as the prototype. */
object MettleIcons {
    private fun icon(name: String, path: String, filled: Boolean = false, stroke: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Home = icon("home", "M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z")
    val Record = icon("record", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8")
    val Progress = icon("progress", "M4 20V10M10 20V4M16 20v-7M22 20H2", stroke = 2.2f)
    val Close = icon("close", "M6 6l12 12M18 6L6 18", stroke = 2.4f)
    val Run = icon("run", "M13 4a1.5 1.5 0 1 0 0 .01M7 21l3-7 3 2v5M10 14l1-5 4 3 3 0M8 9l3-1", stroke = 2.2f)
    val Walk = icon("walk", "M12 4a1.5 1.5 0 1 0 0 .01M9 21l2-6 2 2 1 4M11 15l1-6M12 9l-3 3M12 9l3 3", stroke = 2.2f)
    val Hike = icon("hike", "M3 20l6-11 4 6 3-4 5 9zM9 9l2-4", stroke = 2.2f)
    val Stairs = icon("stairs", "M3 21h5v-5h5v-5h5V6h3", stroke = 2.2f)
    val Back = icon("back", "M15 18l-6-6 6-6", stroke = 2.4f)
    val Chevron = icon("chevron", "M9 6l6 6-6 6", stroke = 2.2f)
    val Day = icon("day", "M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21zM12 7a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5")
    val You = icon("you", "M12 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7")
    val Shield = icon("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z")
    val Heart = icon("heart", "M12 20s-8-4.6-8-11a4.5 4.5 0 0 1 8-2.8A4.5 4.5 0 0 1 20 9c0 6.4-8 11-8 11z")
    val Swap = icon("swap", "M7 4v14M3 14l4 4 4-4M17 20V6M13 10l4-4 4 4")
    val Lab = icon("lab", "M9 3h6M10 3v6L4.5 19a1.5 1.5 0 0 0 1.3 2h12.4a1.5 1.5 0 0 0 1.3-2L14 9V3")
    val Team = icon("team", "M9 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7M2 20c0-3.6 3-6 7-6s7 2.4 7 6M17 5a3 3 0 0 1 0 6M22 20c0-2.6-1.6-4.6-4-5.5")
    val Activity = icon("activity", "M3 12h4l3-8 4 16 3-8h4")
    val Building = icon("building", "M4 21V4h11v17M15 9h5v12M8 8h3M8 12h3M8 16h3M3 21h18")
    val Logout = icon("logout", "M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10")
    val Info = icon("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 11v6M12 7.5v.5")
    val Play = icon("play", "M7 4l13 8-13 8z", filled = true)
    val Pause = icon("pause", "M7 4h4v16H7zM13 4h4v16h-4z", filled = true)
}
