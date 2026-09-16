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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Renders a single video frame: animated route, photos, stats and a watermark. */
class RouteFrameRenderer(private val limits: VideoLimits) {

    private val bgPaint = Paint().apply { color = Color.rgb(22, 28, 38) }
    private val dimPaint = Paint().apply { color = Color.argb(35, 10, 14, 20) }
    /** Satellite tiles lean grey; a modest brightness/contrast lift makes them pop. */
    private val brightenPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    1.18f, 0f, 0f, 0f, 8f,
                    0f, 1.18f, 0f, 0f, 8f,
                    0f, 0f, 1.18f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Thick light underlay for the whole route: readable on satellite imagery.
        color = Color.rgb(245, 245, 245)
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.ROUND
    }
    private val fullAccentPaint = Paint(routePaint).apply {
        // Whole route (A -> B) is drawn in the accent colour from the very first frame.
        color = Color.rgb(255, 160, 0)
        strokeWidth = 6f
    }
    private val trailPaint = Paint(routePaint).apply {
        // Bright moving highlight over the last part of the travelled path.
        color = Color.rgb(255, 235, 59)
        strokeWidth = 13f
    }
    private val startPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 209, 96) }
    private val finishPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Dark halo keeps the marker readable on light and dark imagery.
        color = Color.argb(150, 0, 0, 0)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 87, 34) }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val photoDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) }
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

    /** Source bitmap the cached brightened copy was built from. */
    private var brightenedSource: Bitmap? = null
    private var brightened: Bitmap? = null

    /**
     * The brightness lift is applied ONCE per background bitmap (not per frame):
     * a 640x360 color-matrix pass on 180 frames is pure waste.
     */
    private fun brightenOnce(source: Bitmap): Bitmap {
        brightenedSource?.let { if (it === source) brightened?.let { out -> return out } }
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, 0f, 0f, brightenPaint)
        brightenedSource = source
        brightened = out
        return out
    }
    private val photoCache = object : LruCache<String, Bitmap>(12) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /**
     * World Mercator projection matching the exact crop geometry used by
     * [MapSnapshotProvider] (zoom / tileX / tileY / crop-left / crop-top),
     * so that the route overlay lands on the same pixels as the map backdrop.
     *
     * The snapshot is stretched to the video frame (not aspect-preserving),
     * so X and Y use independent scale factors.
     */
    private class MercatorProjection(
        val zoom: Int,
        val x0: Int,
        val y0: Int,
        val scaleX: Float,
        val scaleY: Float,
        val cropLeftPx: Float,
        val cropTopPx: Float
    ) {
        private fun mercXTiles(lon: Double): Double {
            val n = 1 shl zoom
            return ((lon + 180.0) / 360.0) * n
        }
        private fun mercYTiles(lat: Double): Double {
            val n = 1 shl zoom
            val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            val y = (1.0 - ln(tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0
            return y * n
        }
        private val TILE_PX = 256f

        /** Canvas-space X for a lon. */
        fun x(lon: Double): Float =
            ((mercXTiles(lon) * TILE_PX - cropLeftPx).toFloat()) * scaleX

        /** Canvas-space Y for a lat. */
        fun y(lat: Double): Float =
            ((mercYTiles(lat) * TILE_PX - cropTopPx).toFloat()) * scaleY
    }

    /**
     * Builds the Mercator projection from the single source of truth
     * ([MapSnapshotProvider.cropInfo]), which guarantees that the route
     * overlay lands on exactly the same pixels as the map backdrop.
     *
     * Returns null when the track has fewer than 2 points or no usable zoom
     * is found; in that case the caller must not use the map backdrop either,
     * otherwise the overlay would be drawn in a different coordinate space
     * ("scribbles" on the video).
     */
    private fun buildProjection(
        track: Track,
        w: Float,
        h: Float
    ): MercatorProjection? {
        val info = cropInfo(track, w.toInt(), h.toInt()) ?: return null
        val cropW = info.cropW
        val cropH = info.cropH
        if (cropW <= 0 || cropH <= 0) return null
        val scaleX = w / cropW
        val scaleY = h / cropH
        return MercatorProjection(
            zoom = info.zoom,
            x0 = info.x0,
            y0 = info.y0,
            scaleX = scaleX,
            scaleY = scaleY,
            cropLeftPx = info.leftPx.toFloat(),
            cropTopPx = info.topPx.toFloat()
        )
    }

    /**
     * Delegates to [MapSnapshotProvider.cropInfo] — the single source of truth for
     * the zoom / crop used by both the video backdrop and this overlay.
     */
    private fun cropInfo(track: Track, w: Int, h: Int): MapSnapshotProvider.CropInfo? =
        MapSnapshotProvider.cropInfo(track, w, h)

    fun render(track: Track, frame: Int, frameCount: Int, canvas: Canvas, background: Bitmap? = null) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (background != null) {
            val bright = brightenOnce(background)
            val src = android.graphics.Rect(0, 0, bright.width, bright.height)
            val dst = android.graphics.RectF(0f, 0f, w, h)
            canvas.drawBitmap(bright, src, dst, null)
            canvas.drawRect(0f, 0f, w, h, dimPaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }
        if (track.points.isEmpty()) {
            drawTexts(track, canvas, w, h)
            return
        }
        if (track.points.size < 2) {
            // Degenerate track: still show the single fix instead of an empty frame.
            val centerX = w / 2f
            val centerY = h / 2f
            canvas.drawCircle(centerX, centerY, 22f, dotHaloPaint)
            canvas.drawCircle(centerX, centerY, 15f, dotPaint)
            canvas.drawCircle(centerX, centerY, 15f, dotRingPaint)
            drawTexts(track, canvas, w, h)
            return
        }

        // Use Mercator projection that aligns with the map background when one is present.
        val mercator = if (background != null) buildProjection(track, w, h) else null

        val minLat = track.points.minOf { it.lat }
        val maxLat = track.points.maxOf { it.lat }
        val minLon = track.points.minOf { it.lon }
        val maxLon = track.points.maxOf { it.lon }
        val latRange = max(maxLat - minLat, 1e-6)
        val lonRange = max(maxLon - minLon, 1e-6)

        fun project(lat: Double, lon: Double): Pair<Float, Float> {
            mercator?.let { p ->
                return p.x(lon) to p.y(lat)
            }
            // Fallback: linear projection (no map background).
            val padding = min(w, h) * 0.15f
            val scaleX = (w - 2 * padding) / lonRange
            val scaleY = (h - 2 * padding) / latRange
            val scale = min(scaleX, scaleY)
            val x = padding + ((lon - minLon) * scale).toFloat() +
                (w - 2 * padding - ((maxLon - minLon) * scale).toFloat()) / 2f
            val y = padding + ((maxLat - lat) * scale).toFloat() +
                (h - 2 * padding - ((maxLat - minLat) * scale).toFloat()) / 2f
            return x to y
        }

        val progress = ((frame + 1f) / frameCount).coerceIn(0f, 1f)
        val trackTime = track.startTime + ((track.endTime - track.startTime) * progress).toLong()

        // Full route underlay (always drawn, even for a two-point track).
        val fullPath = android.graphics.Path()
        track.points.forEachIndexed { index, point ->
            val (x, y) = project(point.lat, point.lon)
            if (index == 0) fullPath.moveTo(x, y) else fullPath.lineTo(x, y)
        }
        canvas.drawPath(fullPath, routePaint)
        // Whole route in the accent colour from frame 0 — it never "draws itself in".
        canvas.drawPath(fullPath, fullAccentPaint)

        // Progress runs over the POINT INDEX, not wall-time: a short track still
        // animates the marker along its whole length within the clip duration.
        val n = track.points.size
        val pos = progress * (n - 1)
        val index = floor(pos).toInt().coerceIn(0, (n - 2).coerceAtLeast(0))
        val frac = (pos - index).coerceIn(0f, 1f)
        val next = track.points.getOrElse(index + 1) { track.points[index] }
        val cur = track.points[index]
        val curLat = cur.lat + (next.lat - cur.lat) * frac
        val curLon = cur.lon + (next.lon - cur.lon) * frac

        // Bright trail: the last ~15% of the travelled distance only.
        val tailSpan = max(1f, (n - 1) * 0.15f)
        val tailFrom = (pos - tailSpan).coerceAtLeast(0f)
        val tailIndex = floor(tailFrom).toInt().coerceIn(0, index)
        val tailFrac = (tailFrom - tailIndex).coerceIn(0f, 1f)
        val tailBase = track.points[tailIndex]
        val tailNext = track.points.getOrElse(tailIndex + 1) { tailBase }
        val trail = android.graphics.Path()
        val (tx, ty) = project(
            tailBase.lat + (tailNext.lat - tailBase.lat) * tailFrac,
            tailBase.lon + (tailNext.lon - tailBase.lon) * tailFrac
        )
        trail.moveTo(tx, ty)
        for (k in tailIndex + 1..index) {
            val (px, py) = project(track.points[k].lat, track.points[k].lon)
            trail.lineTo(px, py)
        }
        val (cx0, cy0) = project(curLat, curLon)
        trail.lineTo(cx0, cy0)
        canvas.drawPath(trail, trailPaint)

        // Start (green) and finish (red) end points of the route.
        run {
            val first = track.points.first()
            val last = track.points.last()
            val (sx, sy) = project(first.lat, first.lon)
            val (fx, fy) = project(last.lat, last.lon)
            canvas.drawCircle(sx, sy, 20f, dotHaloPaint)
            canvas.drawCircle(sx, sy, 14f, startPointPaint)
            canvas.drawCircle(sx, sy, 14f, dotRingPaint)
            canvas.drawCircle(fx, fy, 20f, dotHaloPaint)
            canvas.drawCircle(fx, fy, 14f, finishPointPaint)
            canvas.drawCircle(fx, fy, 14f, dotRingPaint)
        }

        // Photo markers sitting on the route at their time positions.
        val markerPhotos = track.photos.sortedBy { it.timestamp }
        for (photo in markerPhotos) {
            val pos = photo.latitude?.let { lat ->
                photo.longitude?.let { lon -> lat to lon }
            } ?: GeoUtils.interpolateByTime(track.points, photo.timestamp)
            if (pos != null) {
                val (mx, my) = project(pos.first, pos.second)
                canvas.drawCircle(mx, my, 10f, dotRingPaint)
                canvas.drawCircle(mx, my, 10f, photoDotPaint)
                canvas.drawCircle(mx, my, 4f, dotRingPaint)
            }
        }

        // Current position marker is drawn LAST (after photo overlays) so it is always
        // on top of the route, markers and photos.

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

        // Moving "me" marker: dark halo + bright centre + white ring, always on top.
        run {
            val (mx, my) = project(curLat, curLon)
            canvas.drawCircle(mx, my, 22f, dotHaloPaint)
            canvas.drawCircle(mx, my, 15f, dotPaint)
            canvas.drawCircle(mx, my, 15f, dotRingPaint)
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