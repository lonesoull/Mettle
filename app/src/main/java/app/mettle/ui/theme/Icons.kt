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
}
