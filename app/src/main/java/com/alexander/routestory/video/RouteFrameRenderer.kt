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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Renders a single video frame: animated route, photos, stats and a watermark. */
class RouteFrameRenderer(private val limits: VideoLimits) {

    private val bgPaint = Paint().apply { color = Color.rgb(22, 28, 38) }
    private val dimPaint = Paint().apply { color = Color.argb(35, 10, 14, 20) }
    /**
     * Sprint 40: ONE brightness lift for every backdrop layer (overview and follow).
     * The old split (1.28x lift for the follow layer, 0.85x downgrade for the overview)
     * made the picture flip between "yellow" and "white" whenever the layer switched.
     * 1.12x / +6 keeps satellite imagery readable without the blown-out look.
     */
    private val brightenPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, 6f,
                    0f, 1.12f, 0f, 0f, 6f,
                    0f, 0f, 1.12f, 0f, 6f,
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
    /** Sprint 41: white play triangle inside the start disc (start != "me"). */
    private val startGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    /** Sprint 41: checkered finish flag cells. */
    private val flagWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val flagBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 24) }
    private val flagPolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 240, 240)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
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
    /** Photo badge disc: the live map uses MaterialTheme.colorScheme.tertiary (Pink40). */
    private val photoDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(125, 82, 96) }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 235, 59) }
    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
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
        /** Light 3D: camera pitch and perspective distance (px). */
        const val TILT_DEGREES = 30f
        const val PERSPECTIVE_Z = 1800f
        /**
         * Sprint 63: extra overscan on top of the corner-derived cover scale. The
         * tilted quad touches the frame border within a fraction of a pixel, and
         * bilinear sampling at that border is what used to leak a dark line.
         */
        const val TILT_COVER_MARGIN = 0.03f
        /** Sanity ceiling so an extreme pitch can never eat the whole composition. */
        const val TILT_COVER_MAX = 1.9f
    }

    /**
     * Sprint 41: brightened copies cached per source bitmap in a small LRU
     * (overview + up to 6 follow segments alternate during MOVE; a single slot
     * re-filtered the full frame every other draw).
     */
    private val brightenCache = object : LruCache<Bitmap, Bitmap>(8) {
        override fun sizeOf(key: Bitmap, value: Bitmap): Int = 1
    }

    /** Light 3D: one perspective matrix, rebuilt only when the frame size changes. */
    private var tiltKey = -1
    private var tiltAmountCached = -1f
    private var tiltMatrix = android.graphics.Matrix()

    /**
     * Perspective matrix for the map group: rotateX around the frame centre, then scale up just
     * enough for the tilted quad to COVER the whole frame.
     *
     * Sprint 63: the old fixed tilt overscan of 1.28 was tuned for one aspect and left under 1 %
     * of vertical overscan on a 16:9 720p frame, which is exactly the thin dark band reported
     * at the top/bottom of some exports. The required overscan now comes from the matrix itself:
     * the four frame corners are mapped through the inverse projection, and the largest corner
     * deviation decides the scale, so the fill is guaranteed for any size/pitch combination.
     * Cheap: computed once per (size, amount) pair.
     *
     * [amount] 0..1 animates the tilt-in (TILT phase); marker still stands.
     */
    private fun tiltFor(w: Float, h: Float, amount: Float = 1f): android.graphics.Matrix {
        val a = amount.coerceIn(0f, 1f)
        val key = ((w.toInt() shl 16) xor h.toInt()) + (a * 100).toInt() * 1_000_003
        if (key != tiltKey || tiltAmountCached != a) {
            tiltKey = key
            tiltAmountCached = a
            val m = android.graphics.Matrix()
            android.graphics.Camera().apply {
                setLocation(0f, 0f, -PERSPECTIVE_Z)
                rotateX(TILT_DEGREES * a)
                getMatrix(m)
            }
            m.preTranslate(-w / 2f, -h / 2f)
            m.postTranslate(w / 2f, h / 2f)
            val cover = tiltCoverScale(m, w, h)
            m.postScale(cover, cover, w / 2f, h / 2f)
            tiltMatrix = m
        }
        return tiltMatrix
    }

    /**
     * Smallest uniform scale (about the frame centre) after which the tilted plane covers
     * the whole frame, plus [TILT_COVER_MARGIN]. Exact w.r.t. the real matrix: a frame
     * corner [c] is covered when the inverse map lands inside the source rect, i.e. when
     * `m.inverse()(centre + (c - centre) / k)` is inside the frame. That predicate is
     * monotone in [k], so a short bisection finds the threshold; the frame has only four
     * corners and the result is cached per (size, amount).
     */
    private fun tiltCoverScale(matrix: android.graphics.Matrix, w: Float, h: Float): Float {
        val inverse = android.graphics.Matrix()
        if (!matrix.invert(inverse)) return (1f + TILT_COVER_MARGIN).coerceAtMost(TILT_COVER_MAX)
        val cx = w / 2f
        val cy = h / 2f
        // Output-space corners of the frame; each is pulled back towards the centre by 1/k.
        val corners = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)

        fun covered(k: Float): Boolean {
            val pts = FloatArray(corners.size)
            var i = 0
            while (i < corners.size) {
                pts[i] = cx + (corners[i] - cx) / k
                pts[i + 1] = cy + (corners[i + 1] - cy) / k
                i += 2
            }
            inverse.mapPoints(pts)
            i = 0
            while (i < pts.size) {
                val x = pts[i]
                val y = pts[i + 1]
                // Outside the source rect (or a degenerate perspective hit) -> not covered.
                if (!x.isFinite() || !y.isFinite() ||
                    x < 0f || x > w || y < 0f || y > h
                ) return false
                i += 2
            }
            return true
        }

        if (covered(1f)) return 1f + TILT_COVER_MARGIN
        var lo = 1f
        var hi = TILT_COVER_MAX
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (covered(mid)) hi = mid else lo = mid
        }
        return min(hi * (1f + TILT_COVER_MARGIN), TILT_COVER_MAX)
    }

    /**
     * The brightness lift and the sharpening pass are applied ONCE per background bitmap (not
     * per frame): a full-frame color-matrix + unsharp pass on 360 frames is pure waste.
     * Sharpen matters because the satellite mosaic is scaled up to the video frame and bilinear
     * filtering softens it.
     *
     * Sprint 41: one lift and a small per-source LRU for both layers (overview and
     * follow segments). Every layer is filtered with the same [brightenPaint], so an
     * update can never flip the picture between two different tints.
     */
    private fun brightenOnce(source: Bitmap): Bitmap {
        brightenCache.get(source)?.let { return it }
        val lit = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(lit).drawBitmap(source, 0f, 0f, brightenPaint)
        val out = if (source.width < 3 || source.height < 3) lit else sharpen(lit)
        brightenCache.put(source, out)
        return out
    }

    /** 3x3 unsharp mask (kernel [0,-1,0, -1,5,-1, 0,-1,0]); one pass per export. */
    private fun sharpen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            val up = if (y > 0) row - w else row
            val down = if (y < h - 1) row + w else row
            for (x in 0 until w) {
                val left = pixels[row + if (x > 0) x - 1 else x]
                val right = pixels[row + if (x < w - 1) x + 1 else x]
                out[row + x] = sharpenPixel(pixels[row + x], left, right, pixels[up + x], pixels[down + x])
            }
        }
        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dst.setPixels(out, 0, w, 0, 0, w, h)
        return dst
    }

    /** Per-channel unsharp with clamping; alpha is preserved. */
    private fun sharpenPixel(center: Int, left: Int, right: Int, up: Int, down: Int): Int {
        var rgb = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val c = (center shr shift) and 0xFF
            // Sprint 40: gentler unsharp (3*c - neighbours/2 instead of 5*c - 4
            // neighbours): the old kernel plus the 1.28x lift looked overcooked.
            val sum = ((left shr shift) and 0xFF) + ((right shr shift) and 0xFF) +
                ((up shr shift) and 0xFF) + ((down shr shift) and 0xFF)
            val value = (c * 6 - sum) / 2
            rgb = rgb or (value.coerceIn(0, 255) shl shift)
        }
        return (center and -0x1000000) or rgb
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
     * Delegates to [MapSnapshotProvider.cropInfo] вЂ” the single source of truth for
     * the zoom / crop used by both the video backdrop and this overlay.
     */
    private fun cropInfo(track: Track, w: Int, h: Int): MapSnapshotProvider.CropInfo? =
        MapSnapshotProvider.cropInfo(track, w, h)


    /**
     * Sprint 40A: follow-segment Mercator projection from the same marker-centred
     * crop the provider stitched. Route/marker/photo pins reuse this projection,
     * so they cannot slide off the tiles.
     */
    /**
     * Sprint 40: the follow segment drawn inside the overview frame.
     * The geometry itself lives in [MapSnapshotProvider.followRectInOverview]
     * (pure, unit-tested); this wrapper only converts it to a RectF.
     */
    private fun followRectInOverview(
        seg: MapSnapshotProvider.CropInfo,
        ov: MapSnapshotProvider.CropInfo?,
        w: Float,
        h: Float
    ): android.graphics.RectF? {
        if (ov == null) return null
        val r = MapSnapshotProvider.followRectInOverview(seg, ov, w.toInt(), h.toInt()) ?: return null
        return android.graphics.RectF(r[0], r[1], r[2], r[3])
    }

    private fun buildCloseUpProjection(
        track: Track,
        w: Float,
        h: Float
    ): MercatorProjection? {
        return null
    }

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
    private var camDrawable: android.graphics.drawable.Drawable? = null

    /**
     * Sprint 41: vector checkered finish flag - pole up from the route end plus a
     * 3x2 checker board. Drawn in the same projection as the overlay, so it sticks
     * to the last track point through zoom/tilt. Sizes scale with the current
     * camera zoom (they are project()-relative, ~30px at 1x).
     */
    private fun drawCheckeredFlag(canvas: Canvas, x: Float, y: Float) {
        val poleH = 54f
        val cell = 10f
        val cols = 3
        val rows = 2
        val poleX = x + 14f
        val top = y - poleH
        canvas.drawLine(poleX, y, poleX, top, flagPolePaint)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val paint = if ((r + c) % 2 == 0) flagWhitePaint else flagBlackPaint
                val l = poleX + c * cell
                val t = top + r * cell
                canvas.drawRect(l, t, l + cell, t + cell, paint)
            }
        }
        // Thin outline keeps the flag readable on bright satellite imagery.
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(poleX, top, poleX + cols * cell, top + rows * cell, outline)
    }

    /** White camera glyph for photo badges (same icon as the live map). */
    private fun cameraGlyph(): android.graphics.drawable.Drawable {
        camDrawable?.let { return it }
        val px = 48
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        // Camera body + top bump + lens: reads as a camera at 24 px on the map.
        c.drawRect(px * 0.16f, px * 0.34f, px * 0.84f, px * 0.78f, body)
        c.drawRect(px * 0.34f, px * 0.22f, px * 0.58f, px * 0.36f, body)
        c.drawCircle(px / 2f, px * 0.56f, px * 0.16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 150, 243) })
        val d = android.graphics.drawable.BitmapDrawable(null, bmp)
        camDrawable = d
        return d
    }

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
        followSegments: List<MapSnapshotProvider.FollowSegment> = emptyList(),
        timeline: RouteVideoTimeline? = null
    ) {
        val tl = timeline ?: timelineFor(track, frameCount)
        val preliminaryFollow = if (tl.followBlend(frame) >= FOLLOW_SWITCH && followSegments.isNotEmpty())
            followSegments[((tl.mapProgress(frame) * followSegments.size).toInt())
                .coerceIn(0, followSegments.size - 1)].bitmap else null
        val closeUpBackground = preliminaryFollow
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        // Sprint 24: fixed intro / outro cards around the map part.
        if (tl.isIntro(frame)) {
            drawIntro(track, tl, frame, canvas, w, h, background)
            return
        }
        if (tl.isOutroCard(frame)) {
            drawOutro(tl, frame, canvas, w, h)
            return
        }
        // Dark base under the tilted map, so the perspective corners never show through.
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.save()
        // TILT phase: tilt 0->30 deg, marker stands. ZOOM phase: navigator zoom to start.
        canvas.concat(tiltFor(w, h, tl.tiltAmount(frame)))
        // Sprint 40: ONE backdrop pass for every frame. The camera pass below draws
        // the overview and (in MOVE) the sharp follow sub-rect with the same paint;
        // without tiles the schematic grid is used instead.
        val hasBgs = background != null || closeUpBackground != null
        if (!hasBgs) {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            drawSchematicGrid(canvas, w, h)
        }
        if (track.points.isEmpty()) {
            canvas.restore()
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
            canvas.restore()
            drawTexts(track, canvas, w, h)
            return
        }

        // Use Mercator projection that aligns with the map background when one is present.
        // Sprint 40A: follow frames use the nearest prebuilt segment projection
        // (same marker-centred crop the provider stitched); overview otherwise.
        // Sprint 40: overlay is ALWAYS the overview projection for the whole MOVE.
        // Follow tiles are a positioned sub-rectangle inside these same coordinates,
        // never a second coordinate space (this was the map "drift" root cause).
        val mercator = if (background != null) buildProjection(track, w, h) else null
        val follow = tl.followBlend(frame)
        val overviewCrop = cropInfo(track, w.toInt(), h.toInt())
        val proj = mercator

        val minLat = track.points.minOf { it.lat }
        val maxLat = track.points.maxOf { it.lat }
        val minLon = track.points.minOf { it.lon }
        val maxLon = track.points.maxOf { it.lon }
        val latRange = max(maxLat - minLat, 1e-6)
        val lonRange = max(maxLon - minLon, 1e-6)

        fun baseProject(lat: Double, lon: Double): Pair<Float, Float> {
            proj?.let { p ->
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

        // Sprint 34: smooth camera follow (was binary isMarkerHeld).
        // follow: 0 at tilt end -> 1 across ZOOM -> 1 through MOVE.
        // zoomRamp: 1.0 -> 4.0 over ZOOM.
        // cameraZoom = zoomRamp + 0.6 * follow:
        //   at ZOOM start: zoomRamp=1.0, follow~0 -> camZoom~1.0
        //   at ZOOM end:   zoomRamp=4.0, follow=1 -> camZoom=4.6  (+15% vs old 4.0)
        //   during MOVE:   zoomRamp=4.0, follow=1 -> camZoom=4.6  (constant, no jump)
        // Sprint 36: fixed camera zoom (no additive follow term). SPRINT 35's
        // zoomRamp + 0.6 * follow pushed the close-up to 4.6x, re-scaling the
        // bitmap every frame and blurring it. A constant 4.0x keeps the close-up
        // crisp while the camera still glides onto the marker during ZOOM.
        val boost = tl.peakBoost(frame)
        // Sprint 41: the MOVE camera zoom targets a fixed geo window around the
        // marker (followCameraZoom), so long rides no longer pull away; short
        // rides keep the classic 4x. ZOOM still glides 1 -> target smoothly.
        // Sprint 49: FINISH pulls the camera back to the full-route overview (zoom -> 1)
        // and the outro keeps that overview static until the card.
        val finishT = tl.finishAmount(frame)
        val zoomOut = if (tl.isOutro(frame)) 1f else finishT
        val camTarget = overviewCrop?.let { MapSnapshotProvider.followCameraZoom(it) } ?: 6f
        val zoomRamp = (1f + (camTarget - 1f) * tl.zoomAmount(frame)) * (1f + 0.35f * boost)
        // Sprint 66: the camera zoom is the plain timeline ramp again. Sprint 65 (B1)
        // clamped it to 2.2 * bitmapWidth / frameWidth, which silently capped the close-up
        // at ~4x instead of the followCameraZoom target (6..48) and killed both the opening
        // zoom-in and the closing zoom-out. Backdrop sharpness is bought with snapshot
        // resolution (oversample + MAX_TILES) and the follow segment, not by holding the
        // camera back.
        val cameraZoom = zoomRamp * (1f - zoomOut) + zoomOut
        val (baseX, baseY) = baseProject(curLat, curLon)
        // Sprint 36: the camera centre is the marker position itself - recomputed
        // every frame, no lerp toward screen centre, no clamp to the overview edges.
        // In the ZOOM phase it glides from the frame centre onto the start marker;
        // in MOVE (follow >= 0.5) it is locked to the marker so the camera can
        // never drift away.
        val rawCenterX = if (follow >= 0.5f) baseX else (w / 2 + (baseX - w / 2) * follow)
        val rawCenterY = if (follow >= 0.5f) baseY else (h / 2 + (baseY - h / 2) * follow)
        // Sprint 49: while pulling back, pan from the marker to the overview centre so
        // the whole route lands in the frame; the outro stays centred.
        val centerX = w / 2 + (rawCenterX - w / 2) * (1f - finishT)
        val centerY = h / 2 + (rawCenterY - h / 2) * (1f - finishT)
        fun project(lat: Double, lon: Double): Pair<Float, Float> {
            val (x, y) = baseProject(lat, lon)
            return (x - centerX) * cameraZoom + w / 2 to (y - centerY) * cameraZoom + h / 2
        }
        // Sprint 40A: one background per frame. After follow reaches [FOLLOW_SWITCH],
        // choose one prepared segment with the greatest camera coverage.
        // Sprint 63: the overview is no longer dropped during MOVE - it stays as the BASE
        // layer under the sharp segment. A follow crop only covers its own window, so every
        // pixel outside that window used to fall through to [bgPaint] and read as a black
        // band at the top/bottom of the clip. Overview and segment share ONE coordinate
        // space (see FollowRectGeometryTest), so overlaying them keeps the drawn route on
        // the satellite imagery instead of lifting it off.
        data class FollowLayer(
            val index: Int,
            val rect: android.graphics.RectF,
            val bitmap: Bitmap,
            val coverage: Float
        )
        fun layer(index: Int): FollowLayer? {
            if (index !in followSegments.indices) return null
            val segment = followSegments[index]
            val bitmap = segment.bitmap ?: return null
            val rect = followRectInOverview(segment.info, overviewCrop, w, h) ?: return null
            val requiredLeft = centerX - w / (2f * cameraZoom)
            val requiredTop = centerY - h / (2f * cameraZoom)
            val requiredRight = centerX + w / (2f * cameraZoom)
            val requiredBottom = centerY + h / (2f * cameraZoom)
            val overlapW = (minOf(rect.right, requiredRight) - maxOf(rect.left, requiredLeft))
                .coerceAtLeast(0f)
            val overlapH = (minOf(rect.bottom, requiredBottom) - maxOf(rect.top, requiredTop))
                .coerceAtLeast(0f)
            val requiredArea = (w / cameraZoom) * (h / cameraZoom)
            val coverage = (overlapW * overlapH) / requiredArea.coerceAtLeast(1f)
            return FollowLayer(index, rect, bitmap, coverage)
        }
        // Sprint 66: the sharp layer is back to the MOVE phase only, gated by FOLLOW_SWITCH.
        //
        // Sprint 65 (B2) admitted it from 0.60 and dropped the !isFinish guard. Both were
        // wrong: followCropInfo caps its zoom via
        // camAtSwitch = 1 + FOLLOW_SWITCH * (camZoom - 1), i.e. the segment rect is sized
        // to cover the camera window at 87% of the ramp, NOT at 60%. With the camera then
        // also held back by the backdrop cap, the required window was 2-3x the rect, so
        // maxByOrNull picked a segment that covered part of the frame and the rest stayed
        // on the coarse overview - the "small HQ square floating over blurred map".
        // On FINISH the same rule pulled the last segment along with the retreating
        // camera, drawing the square during the zoom-out.
        //
        // Below FOLLOW_SWITCH (and through ZOOM and FINISH) the frame is ONE layer: the
        // overview, cover-scaled, so no seam can appear.
        val isMove = follow >= FOLLOW_SWITCH && !tl.isFinish(frame)
        val candidates = if (isMove) followSegments.indices.mapNotNull(::layer) else emptyList()
        val sharpLayer = candidates.maxByOrNull { it.coverage }
        // Sprint 63: the overview is the base layer whenever tiles are available; the sharp
        // segment only paints over it. No frame region can reach [bgPaint] anymore. When the
        // overview snapshot is missing, the follow crop itself stretches over the frame as
        // the base (the pre-Sprint-63 behaviour of the intro camera), which still beats a
        // black band at the frame edge.
        val overviewBase = background ?: closeUpBackground
        if (frame % 30 == 0 && isMove) {
            android.util.Log.d(
                "VideoExport",
                "follow segmentIndex=${sharpLayer?.index ?: -1} " +
                    "coversFrame=${sharpLayer != null && sharpLayer.coverage >= 0.999f} " +
                    "coverage=${"%.3f".format(sharpLayer?.coverage ?: 0f)} overviewBase=${background != null}"
            )
        }
        if (overviewBase != null || sharpLayer != null) {
            canvas.save()
            canvas.translate(w / 2, h / 2)
            canvas.scale(cameraZoom, cameraZoom)
            canvas.translate(-centerX, -centerY)
            // 1) Full base: always stretches over the whole frame rect.
            overviewBase?.let {
                canvas.drawBitmap(brightenOnce(it), null,
                    android.graphics.RectF(0f, 0f, w, h), null)
            }
            // 2) The sharp follow crop on top, inside the very same overview space.
            sharpLayer?.let { layer ->
                canvas.drawBitmap(brightenOnce(layer.bitmap), null, layer.rect, null)
            }
            canvas.restore()
        }
        if (background != null || closeUpBackground != null) {
            canvas.drawRect(0f, 0f, w, h, dimPaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            drawSchematicGrid(canvas, w, h)
        }
        if (frame % limits.fps == 0 || frame == frameCount - 1) {
            android.util.Log.i(
                "MapSnapshot",
                "videoCam z=${mercator?.zoom ?: 0} follow=${"%.2f".format(follow)} camZ=$cameraZoom " +
                    "pointIndex=${tl.pointIndex(progress)} photo=${tl.photoAt(frame)?.let { "yes" } ?: "no"}"
            )
        }

        // Full route underlay (always drawn, even for a two-point track).
        val fullPath = android.graphics.Path()
        track.points.forEachIndexed { index, point ->
            val (x, y) = project(point.lat, point.lon)
            if (index == 0) fullPath.moveTo(x, y) else fullPath.lineTo(x, y)
        }
        canvas.drawPath(fullPath, routePaint)
        // Whole route in the accent colour from frame 0 вЂ” it never "draws itself in".
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

        // Sprint 41: START must not look like the moving "me" marker (orange disc +
        // white ring): start = green disc + white play triangle, no ring. FINISH =
        // checkered flag on a short pole (vector, no assets), red dot at the tip of
        // the route end stays as the geographic anchor.
        run {
            val first = track.points.first()
            val last = track.points.last()
            val (sx, sy) = project(first.lat, first.lon)
            val (fx, fy) = project(last.lat, last.lon)
            canvas.drawCircle(sx, sy, 20f, dotHaloPaint)
            canvas.drawCircle(sx, sy, 14f, startPointPaint)
            val tri = android.graphics.Path().apply {
                moveTo(sx - 5f, sy - 7f)
                lineTo(sx + 8f, sy)
                lineTo(sx - 5f, sy + 7f)
                close()
            }
            canvas.drawPath(tri, startGlyphPaint)
            canvas.drawCircle(fx, fy, 20f, dotHaloPaint)
            canvas.drawCircle(fx, fy, 14f, finishPointPaint)
            drawCheckeredFlag(canvas, fx, fy)
        }

        // Photo badges like live: dark halo + brand disc + white camera glyph.
        // Sprint 41: the pin is drawn from insertFraction - the SAME value the
        // marker freezes on - so pin and marker can never use different geos.
        val camGlyph = cameraGlyph()
        for ((pinIndex, _) in tl.inserts.withIndex()) {
            val f = tl.insertFraction(pinIndex)
            val pp = tl.motion.position(f)
            val pi = floor(pp).toInt().coerceIn(0, n - 1)
            val pt = (pp - pi).coerceIn(0f, 1f)
            val pa = track.points[pi]
            val pb = track.points[(pi + 1).coerceAtMost(n - 1)]
            val pos = (pa.lat + (pb.lat - pa.lat) * pt) to (pa.lon + (pb.lon - pa.lon) * pt)
            run {
                val (mx, my) = project(pos.first, pos.second)
                // Sprint 40: same badge as the live map - dark halo + disc + white camera
                // glyph and no extra white ring (the ring made it read as a plain dot).
                canvas.drawCircle(mx, my, 19f, dotHaloPaint)
                canvas.drawCircle(mx, my, 16f, photoDotPaint)
                val gs = 24f
                camGlyph.setBounds(
                    (mx - gs / 2).toInt(), (my - gs / 2).toInt(),
                    (mx + gs / 2).toInt(), (my + gs / 2).toInt()
                )
                camGlyph.draw(canvas)
            }
        }

        // Max-speed peak: small pin always, label + pulse only while the marker is on it.
        tl.peakFraction?.let { pf ->
            val pm = tl.motion
            val pp = pm.position(pf)
            val pi = floor(pp).toInt().coerceIn(0, n - 2)
            val pf2 = (pp - pi).coerceIn(0f, 1f)
            val pa = track.points[pi]
            val pb = track.points[pi + 1]
            val plat = pa.lat + (pb.lat - pa.lat) * pf2
            val plon = pa.lon + (pb.lon - pa.lon) * pf2
            val (px, py) = project(plat, plon)
            canvas.drawCircle(px, py, 20f, dotHaloPaint)
            canvas.drawCircle(px, py, 14f, peakPaint)
            canvas.drawCircle(px, py, 14f, dotRingPaint)
            if (tl.isPeakActive(frame)) {
                canvas.drawCircle(px, py, 24f, peakPaint)
                val label = GeoUtils.formatSpeed(tl.peakSpeedMps)
                canvas.drawText(label, px + 22f, py - 18f, peakTextPaint)
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
        // End of the tilted map group: the photo insert and the HUD stay flat and unskewed.
        canvas.restore()

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
        val stats = GeoUtils.formatDistance(track.distance) + "  В·  " +
            GeoUtils.formatDuration(track.duration) + "  В·  " + GeoUtils.formatSpeed(avgSpeed)
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
