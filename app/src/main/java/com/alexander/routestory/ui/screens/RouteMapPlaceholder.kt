package com.alexander.routestory.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.alexander.routestory.domain.TrackPoint
import kotlin.math.max
import kotlin.math.min

/** Simple canvas preview of the recorded route until a real map is integrated. */
@Composable
fun RouteMapPlaceholder(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val latRange = max(maxLat - minLat, 1e-6)
        val lonRange = max(maxLon - minLon, 1e-6)
        val padding = 32.dp.toPx()
        val scaleX = (size.width - 2 * padding) / lonRange.toFloat()
        val scaleY = (size.height - 2 * padding) / latRange.toFloat()
        val scale = min(scaleX, scaleY)
        fun offsetX(lon: Double): Float =
            padding + ((lon - minLon) * scale).toFloat() +
                (size.width - 2 * padding - ((maxLon - minLon) * scale).toFloat()) / 2f
        fun offsetY(lat: Double): Float =
            padding + ((maxLat - lat) * scale).toFloat() +
                (size.height - 2 * padding - ((maxLat - minLat) * scale).toFloat()) / 2f
        val path = Path()
        points.forEachIndexed { index, point ->
            val offset = Offset(offsetX(point.lon), offsetY(point.lat))
            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 6f))
    }
}