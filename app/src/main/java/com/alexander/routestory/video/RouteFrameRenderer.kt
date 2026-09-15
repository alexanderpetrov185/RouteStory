package com.alexander.routestory.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import com.alexander.routestory.domain.Track
import com.alexander.routestory.utils.GeoUtils
import kotlin.math.max
import kotlin.math.min

/** Renders a single video frame: animated route, photos, stats and a watermark. */
class RouteFrameRenderer(private val limits: VideoLimits) {

    private val bgPaint = Paint().apply { color = Color.rgb(22, 28, 38) }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 90, 120)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val trailPaint = Paint(routePaint).apply {
        color = Color.rgb(66, 165, 245)
        strokeWidth = 9f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 87, 34) }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 34f
    }
    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val photoCache = object : LruCache<String, Bitmap>(12) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun render(track: Track, frame: Int, frameCount: Int, canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (track.points.size < 2) {
            drawTexts(track, canvas, w, h)
            return
        }
        val minLat = track.points.minOf { it.lat }
        val maxLat = track.points.maxOf { it.lat }
        val minLon = track.points.minOf { it.lon }
        val maxLon = track.points.maxOf { it.lon }
        val latRange = max(maxLat - minLat, 1e-6)
        val lonRange = max(maxLon - minLon, 1e-6)

        fun project(lat: Double, lon: Double): Pair<Float, Float> {
            val padding = min(w, h) * 0.15f
            val scaleX = (w - 2 * padding) / lonRange.toFloat()
            val scaleY = (h - 2 * padding) / latRange.toFloat()
            val scale = min(scaleX, scaleY)
            val x = padding + ((lon - minLon) * scale).toFloat() +
                (w - 2 * padding - ((maxLon - minLon) * scale).toFloat()) / 2f
            val y = padding + ((maxLat - lat) * scale).toFloat() +
                (h - 2 * padding - ((maxLat - minLat) * scale).toFloat()) / 2f
            return x to y
        }

        val progress = (frame + 1f) / frameCount
        val trackTime = track.startTime + ((track.endTime - track.startTime) * progress).toLong()

        // Full route, dimmed
        val fullPath = android.graphics.Path()
        track.points.forEachIndexed { index, point ->
            val (x, y) = project(point.lat, point.lon)
            if (index == 0) fullPath.moveTo(x, y) else fullPath.lineTo(x, y)
        }
        canvas.drawPath(fullPath, routePaint)

        // Trail up to the current time
        val trail = android.graphics.Path()
        var started = false
        for (point in track.points) {
            if (point.timestamp > trackTime) break
            val (x, y) = project(point.lat, point.lon)
            if (!started) { trail.moveTo(x, y); started = true } else trail.lineTo(x, y)
        }
        if (started) canvas.drawPath(trail, trailPaint)

        // Current position marker
        val current = GeoUtils.interpolateByTime(track.points, trackTime)
        if (current != null) {
            val (cx, cy) = project(current.first, current.second)
            canvas.drawCircle(cx, cy, 14f, dotPaint)
            canvas.drawCircle(cx, cy, 14f, dotRingPaint)
        }

        // Photo overlays with fade in/out (about 0.6 s at the video timeline)
        val fadeFrames = (limits.fps * 0.6f).toInt().coerceAtLeast(1)
        val photos = track.photos.sortedBy { kotlin.math.abs(it.timestamp - trackTime) }.take(limits.maxPhotos)
        for (photo in photos) {
            val photoProgress =
                ((photo.timestamp - track.startTime).toFloat() / (track.endTime - track.startTime).coerceAtLeast(1)).coerceIn(0f, 1f)
            val photoFrame = photoProgress * frameCount
            val distance = frame - photoFrame
            if (distance < -fadeFrames || distance > fadeFrames) continue
            val alpha = (1f - kotlin.math.abs(distance) / fadeFrames).coerceIn(0f, 1f)
            val bitmap = decodePhoto(photo.uri, 720)
            if (bitmap != null) {
                photoPaint.alpha = (alpha * 255).toInt()
                val targetH = h * 0.55f
                val scale = targetH / bitmap.height
                val drawW = bitmap.width * scale
                canvas.drawBitmap(bitmap, (w - drawW) / 2f, (h - targetH) / 2f, photoPaint)
            }
        }

        drawTexts(track, canvas, w, h)
    }

    private fun drawTexts(track: Track, canvas: Canvas, w: Float, h: Float) {
        canvas.drawText(track.name, 28f, 64f, titlePaint)
        canvas.drawText(
            GeoUtils.formatDistance(track.distance) + ", " + GeoUtils.formatDuration(track.duration),
            28f, h - 74f, statsPaint
        )
        limits.watermark?.let { wm ->
            canvas.drawText(wm, w - 28f - watermarkPaint.measureText(wm), h - 28f, watermarkPaint)
        }
    }

    private fun decodePhoto(path: String, maxDim: Int): Bitmap? {
        photoCache.get(path)?.let { return it }
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        photoCache.put(path, bitmap)
        return bitmap
    }
}