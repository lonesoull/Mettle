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
fun RouteSketch(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    if (points.size < 5) return   // a couple of GPS fixes is not a route worth drawing
    val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
    val minLon = points.minOf { it.second }; val maxLon = points.maxOf { it.second }
    val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))
    Canvas(modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)).background(MettleColors.Band)
        .semantics { contentDescription = "Route shape" }) {
        val w = (maxLon - minLon) * lonScale; val h = maxLat - minLat
        val pad = 16.dp.toPx()
        val s = minOf((size.width - 2 * pad) / w.coerceAtLeast(1e-6), (size.height - 2 * pad) / h.coerceAtLeast(1e-6))
        val ox = (size.width - w * s) / 2; val oy = (size.height - h * s) / 2
        val path = Path()
        points.forEachIndexed { i, (la, lo) ->
            val x = (ox + (lo - minLon) * lonScale * s).toFloat(); val y = (size.height - oy - (la - minLat) * s).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, MettleColors.Surface, style = Stroke(10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, MettleColors.Orange, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
