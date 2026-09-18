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
    /** Solid wash behind a full-screen photo so the map does not bleed through the fades. */
    private val photoBackdropPaint = Paint().apply { color = Color.BLACK }

    /** Sprint 24: intro/outro card typography and heavy dim over the backdrop. */
    private val cardDimPaint = Paint().apply { color = Color.argb(200, 10, 14, 20) }
    private val schematicGridPaint = Paint().apply {
        color = Color.argb(38, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val schematicLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 160, 0)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val appNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val introRidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        textSize = 40f
    }
    private val introStatsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        textSize = 32f
    }
    private val outroStorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 200, 210, 220)
        textSize = 30f
    }

    private companion object {
        /** Brand line shown on the intro and outro cards. */
        const val APP_NAME = "RouteStory"
    }

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
            cropLeftPx = (info.x0 * 256 + info.leftPx).toFloat(),
            cropTopPx = (info.y0 * 256 + info.topPx).toFloat()
        )
    }

    /**
     * Delegates to [MapSnapshotProvider.cropInfo] — the single source of truth for
     * the zoom / crop used by both the video backdrop and this overlay.
     */
    private fun cropInfo(track: Track, w: Int, h: Int): MapSnapshotProvider.CropInfo? =
        MapSnapshotProvider.cropInfo(track, w, h)

    private fun drawSchematicGrid(canvas: Canvas, w: Float, h: Float) {
        val step = min(w, h) / 8f
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, schematicGridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, schematicGridPaint)
            y += step
        }
    }

    private var cachedTimelineTrack: Track? = null
    private var cachedTimelineFrameCount = -1
    private var cachedTimeline: RouteVideoTimeline? = null

    /** Timeline is rebuilt only when a different track arrives, never per frame. */
    private fun timelineFor(track: Track, frameCount: Int): RouteVideoTimeline {
        val cached = cachedTimeline
        if (cached != null && cachedTimelineTrack === track && cachedTimelineFrameCount == frameCount) return cached
        val built = RouteVideoTimeline(track, limits.fps, limits.durationSec, limits.maxPhotos)
        cachedTimelineTrack = track
        cachedTimelineFrameCount = frameCount
        cachedTimeline = built
        return built
    }

    fun render(
        track: Track,
        frame: Int,
        frameCount: Int,
        canvas: Canvas,
        background: Bitmap? = null,
        timeline: RouteVideoTimeline? = null
    ) {
        val tl = timeline ?: timelineFor(track, frameCount)
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        // Sprint 24: fixed intro / outro cards around the map part.
        if (tl.isIntro(frame)) {
            drawIntro(track, tl, frame, canvas, w, h, background)
            return
        }
        if (tl.isOutro(frame)) {
            drawOutro(tl, frame, canvas, w, h)
            return
        }
        if (background != null) {
            val bright = brightenOnce(background)
            val src = android.graphics.Rect(0, 0, bright.width, bright.height)
            val dst = android.graphics.RectF(0f, 0f, w, h)
            canvas.drawBitmap(bright, src, dst, null)
            canvas.drawRect(0f, 0f, w, h, dimPaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            drawSchematicGrid(canvas, w, h)
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

        fun baseProject(lat: Double, lon: Double): Pair<Float, Float> {
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

        val progress = tl.mapProgress(frame)
        val motion = tl.motion
        val n = track.points.size
        val pos = motion.position(progress)
        val index = floor(pos).toInt().coerceIn(0, n - 2)
        val frac = (pos - index).coerceIn(0f, 1f)
        val cur = track.points[index]
        val next = track.points[index + 1]
        val curLat = cur.lat + (next.lat - cur.lat) * frac
        val curLon = cur.lon + (next.lon - cur.lon) * frac
        val blend = RouteVideoMotion.followBlend(progress)
        val cameraZoom = 1f + blend
        val (baseX, baseY) = baseProject(curLat, curLon)
        // Clamp the follow window to the downloaded overview; no empty edges.
        val centerX = (w / 2 + (baseX - w / 2) * blend)
            .coerceIn(w / (2 * cameraZoom), w - w / (2 * cameraZoom))
        val centerY = (h / 2 + (baseY - h / 2) * blend)
            .coerceIn(h / (2 * cameraZoom), h - h / (2 * cameraZoom))
        fun project(lat: Double, lon: Double): Pair<Float, Float> {
            val (x, y) = baseProject(lat, lon)
            return (x - centerX) * cameraZoom + w / 2 to (y - centerY) * cameraZoom + h / 2
        }
        if (background != null) {
            canvas.save()
            canvas.translate(w / 2, h / 2)
            canvas.scale(cameraZoom, cameraZoom)
            canvas.translate(-centerX, -centerY)
            canvas.drawBitmap(brightenOnce(background), null, android.graphics.RectF(0f, 0f, w, h), null)
            canvas.restore()
            canvas.drawRect(0f, 0f, w, h, dimPaint)
        }
        if (frame % limits.fps == 0 || frame == frameCount - 1) {
            android.util.Log.i(
                "MapSnapshot",
                "videoCam z=${mercator?.zoom ?: 0} phase=${if (blend == 0f) "overview" else "follow"} " +
                    "pointIndex=${tl.pointIndex(progress)} zoom=$cameraZoom photo=${tl.photoAt(frame)?.let { "yes" } ?: "no"}"
            )
        }

        // Full route underlay (always drawn, even for a two-point track).
        val fullPath = android.graphics.Path()
        track.points.forEachIndexed { index, point ->
            val (x, y) = project(point.lat, point.lon)
            if (index == 0) fullPath.moveTo(x, y) else fullPath.lineTo(x, y)
        }
        canvas.drawPath(fullPath, routePaint)
        // Whole route in the accent colour from frame 0 — it never "draws itself in".
        canvas.drawPath(fullPath, fullAccentPaint)

        // Passed trail: everything 0 -> current point stays highlighted (amber),
        // the not-yet-travelled remainder keeps the muted full-route colour behind.
        val passed = android.graphics.Path()
        val first = track.points.first()
        val (fx0, fy0) = project(first.lat, first.lon)
        passed.moveTo(fx0, fy0)
        for (k in 1..index) {
            val (px, py) = project(track.points[k].lat, track.points[k].lon)
            passed.lineTo(px, py)
        }
        val (cx0, cy0) = project(curLat, curLon)
        passed.lineTo(cx0, cy0)
        canvas.drawPath(passed, trailPaint)

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

        // Photo markers sitting on the route at their time positions. Only the photos that
        // really get a full-screen insert are pinned, so the map never promises more than
        // the clip delivers.
        for (photo in tl.inserts) {
            val pos = photo.latitude?.let { lat ->
                photo.longitude?.let { lon -> lat to lon }
            } ?: GeoUtils.interpolateByTime(track.points, photo.timestamp)
            if (pos != null) {
                val (mx, my) = project(pos.first, pos.second)
                canvas.drawCircle(mx, my, 16f, dotHaloPaint)
                canvas.drawCircle(mx, my, 11f, photoDotPaint)
                canvas.drawCircle(mx, my, 11f, dotRingPaint)
                canvas.drawCircle(mx, my, 4f, dotHaloPaint)
            }
        }

        // Current position marker is drawn LAST (after route, markers and photos) so it is
        // always on top of the route and the photo pins.

        // Moving "me" marker: dark halo + bright centre + white ring, always on top.
        run {
            val (mx, my) = project(curLat, curLon)
            canvas.drawCircle(mx, my, 22f, dotHaloPaint)
            canvas.drawCircle(mx, my, 15f, dotPaint)
            canvas.drawCircle(mx, my, 15f, dotRingPaint)
        }

        // Full-screen photo insert: shown when the moving marker reaches the photo position.
        // The map is paused (see RouteVideoTimeline.mapProgress), so the marker literally
        // stops where the shot was taken. Fade in/out is at most PHOTO_FADE_SEC.
        tl.photoAt(frame)?.let { photo ->
            val alpha = tl.photoAlpha(frame)
            if (alpha > 0f) {
                // Solid backdrop so the map never bleeds through the photo.
                canvas.drawRect(0f, 0f, w, h, photoBackdropPaint.apply { this.alpha = (alpha * 255).toInt() })
                val bitmap = decodePhoto(photo.uri, 1080)
                if (bitmap != null) {
                    // Fit the whole photo: never crop the subject out of frame.
                    val scale = min(w / bitmap.width, h / bitmap.height)
                    val drawW = bitmap.width * scale
                    val drawH = bitmap.height * scale
                    photoPaint.alpha = (alpha * 255).toInt()
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        android.graphics.RectF((w - drawW) / 2f, (h - drawH) / 2f, (w + drawW) / 2f, (h + drawH) / 2f),
                        photoPaint
                    )
                }
            }
        }

        drawTexts(track, canvas, w, h)
    }

    /** Intro card: dark/sat-dimmed backdrop + app name, ride name, one stats line. */
    private fun drawIntro(
        track: Track,
        tl: RouteVideoTimeline,
        frame: Int,
        canvas: Canvas,
        w: Float,
        h: Float,
        background: Bitmap?
    ) {
        if (background != null) {
            canvas.drawBitmap(
                brightenOnce(background), null,
                android.graphics.RectF(0f, 0f, w, h), null
            )
            canvas.drawRect(0f, 0f, w, h, cardDimPaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }
        val alpha = (tl.introAlpha(frame) * 255).toInt()
        appNamePaint.alpha = alpha
        introRidePaint.alpha = alpha
        introStatsPaint.alpha = alpha
        val cy = h / 2f
        canvas.drawText(APP_NAME, w / 2f - appNamePaint.measureText(APP_NAME) / 2f, cy - 46f, appNamePaint)
        val ride = track.name
        canvas.drawText(ride, w / 2f - introRidePaint.measureText(ride) / 2f, cy + 22f, introRidePaint)
        val avgSpeed = if (track.duration > 0) track.distance / (track.duration / 1000f) else 0f
        val stats = GeoUtils.formatDistance(track.distance) + "  ·  " +
            GeoUtils.formatDuration(track.duration) + "  ·  " + GeoUtils.formatSpeed(avgSpeed)
        canvas.drawText(stats, w / 2f - introStatsPaint.measureText(stats) / 2f, cy + 82f, introStatsPaint)
    }

    /** Outro card: app name + store line, fade-in capped at CARD_FADE_SEC. */
    private fun drawOutro(tl: RouteVideoTimeline, frame: Int, canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val alpha = (tl.outroAlpha(frame) * 255).toInt()
        appNamePaint.alpha = alpha
        outroStorePaint.alpha = alpha
        canvas.drawText(APP_NAME, w / 2f - appNamePaint.measureText(APP_NAME) / 2f, h / 2f + 12f, appNamePaint)
        val store = "Available on Google Play"
        canvas.drawText(store, w / 2f - outroStorePaint.measureText(store) / 2f, h / 2f + 78f, outroStorePaint)
    }

    /** Watermark only during the map part: no stats or long captions over the moving route. */
    private fun drawTexts(track: Track, canvas: Canvas, w: Float, h: Float) {
        limits.watermark?.let { wm ->
            canvas.drawText(wm, w - 28f - watermarkPaint.measureText(wm), h - 28f, watermarkPaint)
        }
    }

    private fun decodePhoto(path: String, maxDim: Int): Bitmap? {
        photoCache.get(path)?.let { return it }
        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        val bitmap = applyExifRotation(path, decoded)
        photoCache.put(path, bitmap)
        return bitmap
    }

    /** Camera photos are often stored rotated; without this the insert is sideways. */
    private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            androidx.exifinterface.media.ExifInterface(path)
                .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 0)
        }.getOrDefault(0)
        val matrix = android.graphics.Matrix()
        when (orientation) {
            3 -> matrix.postRotate(180f)
            6 -> matrix.postRotate(90f)
            8 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }.getOrDefault(bitmap)
    }
}