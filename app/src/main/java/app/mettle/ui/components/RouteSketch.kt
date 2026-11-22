package app.mettle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mettle.ui.theme.MettleColors
import kotlin.math.cos

/**
 * Lightweight route drawing for lists (no map tiles): instant, works offline, never lags while scrolling.
 */
@Composable
fun RouteSketch(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier, height: Int = 140, rounded: Boolean = true) {
    if (points.size < 5) return   // a couple of GPS fixes is not a route worth drawing
    val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
    val minLon = points.minOf { it.second }; val maxLon = points.maxOf { it.second }
    val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))
    val shape = if (rounded) RoundedCornerShape(12.dp) else RoundedCornerShape(0.dp)
    Canvas(modifier.fillMaxWidth().height(height.dp).clip(shape).background(SketchGround)
        .semantics { contentDescription = "Route shape" }) {
        // Faint street-like grid so the sketch reads as a map, not an outline on grey.
        val step = 28.dp.toPx()
        var gx = step / 2; while (gx < size.width) { drawLine(SketchGrid, androidx.compose.ui.geometry.Offset(gx, 0f), androidx.compose.ui.geometry.Offset(gx, size.height), 1.5f); gx += step }
        var gy = step / 2; while (gy < size.height) { drawLine(SketchGrid, androidx.compose.ui.geometry.Offset(0f, gy), androidx.compose.ui.geometry.Offset(size.width, gy), 1.5f); gy += step }
        val w = (maxLon - minLon) * lonScale; val h = maxLat - minLat
        val pad = 24.dp.toPx()
        val s = minOf((size.width - 2 * pad) / w.coerceAtLeast(1e-6), (size.height - 2 * pad) / h.coerceAtLeast(1e-6))
        val ox = (size.width - w * s) / 2; val oy = (size.height - h * s) / 2
        val path = Path()
        var first = androidx.compose.ui.geometry.Offset.Zero; var last = first
        points.forEachIndexed { i, (la, lo) ->
            val x = (ox + (lo - minLon) * lonScale * s).toFloat(); val y = (size.height - oy - (la - minLat) * s).toFloat()
            if (i == 0) { path.moveTo(x, y); first = androidx.compose.ui.geometry.Offset(x, y) } else path.lineTo(x, y)
            last = androidx.compose.ui.geometry.Offset(x, y)
        }
        drawPath(path, MettleColors.Surface, style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, MettleColors.Orange, style = Stroke(5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(MettleColors.Surface, 9f, first); drawCircle(MettleColors.Good, 6f, first)
        drawCircle(MettleColors.Surface, 9f, last); drawCircle(MettleColors.Ink, 6f, last)
    }
}

private val SketchGround = androidx.compose.ui.graphics.Color(0xFFEDEFF2)
private val SketchGrid = androidx.compose.ui.graphics.Color(0xFFE2E5E9)
