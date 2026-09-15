package com.alexander.routestory.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.alexander.routestory.domain.TrackPoint
import kotlin.math.max
import kotlin.math.min

/**
 * Simple canvas preview of the recorded route until a real map is integrated.
 * Optionally draws photo markers and reports taps on them.
 */
@Composable
fun RouteMapPlaceholder(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    photoPositions: List<Pair<Double, Double>> = emptyList(),
    onPhotoTap: ((Int) -> Unit)? = null
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.tertiary
    val hasRoute = points.size >= 2

    val minLat = if (hasRoute) points.minOf { it.lat } else 0.0
    val maxLat = if (hasRoute) points.maxOf { it.lat } else 0.0
    val minLon = if (hasRoute) points.minOf { it.lon } else 0.0
    val maxLon = if (hasRoute) points.maxOf { it.lon } else 0.0
    val latRange = max(maxLat - minLat, 1e-6)
    val lonRange = max(maxLon - minLon, 1e-6)

    fun project(lat: Double, lon: Double, width: Float, height: Float): Offset {
        val padding = min(width, height) * 0.1f
        val scaleX = (width - 2 * padding) / lonRange.toFloat()
        val scaleY = (height - 2 * padding) / latRange.toFloat()
        val scale = min(scaleX, scaleY)
        val x = padding + ((lon - minLon) * scale).toFloat() +
            (width - 2 * padding - ((maxLon - minLon) * scale).toFloat()) / 2f
        val y = padding + ((maxLat - lat) * scale).toFloat() +
            (height - 2 * padding - ((maxLat - minLat) * scale).toFloat()) / 2f
        return Offset(x, y)
    }

    val tapModifier = if (onPhotoTap != null && photoPositions.isNotEmpty()) {
        Modifier.pointerInput(photoPositions) {
            detectTapGestures { tap ->
                val hitRadius = 30f
                val index = photoPositions.indices.firstOrNull { i ->
                    val pos = photoPositions[i]
                    val o = project(pos.first, pos.second, size.width.toFloat(), size.height.toFloat())
                    val dx = tap.x - o.x
                    val dy = tap.y - o.y
                    dx * dx + dy * dy <= hitRadius * hitRadius
                }
                if (index != null) onPhotoTap(index)
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.then(tapModifier)) {
        if (hasRoute) {
            val path = Path()
            points.forEachIndexed { index, point ->
                val offset = project(point.lat, point.lon, size.width, size.height)
                if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
            }
            drawPath(path, color = lineColor, style = Stroke(width = 6f))
        }
        photoPositions.forEach { pos ->
            val offset = project(pos.first, pos.second, size.width, size.height)
            drawCircle(markerColor, radius = 14f, center = offset)
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = 5f, center = offset)
        }
    }
}