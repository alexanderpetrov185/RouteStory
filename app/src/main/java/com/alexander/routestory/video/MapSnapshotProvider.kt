package com.alexander.routestory.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.alexander.routestory.domain.Track
import com.alexander.routestory.utils.MapTiles
import com.alexander.routestory.utils.TileDiskCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Sprint 49: one stitched RGB_565 follow crop is at most 1536x1536 (about 4.5 MiB).
 * 48 segments cover long rides while keeping the follow bitmap batch bounded at
 * roughly 220 MiB before the overview; the exact count is logged on failure.
 */
internal const val MAX_FOLLOW_SEGMENTS = 48

/** Hard budget for follow bitmaps held in memory during one export. */

/**
 * Sprint 41 follow detail: the follow zoom is derived from a geo WINDOW around the
 * marker (~[FOLLOW_WINDOW_M] across the screen during MOVE), not from the whole-track
 * overview bbox - long rides no longer "zoom out".
 *
 * Note: the effective follow zoom is computed by [followCropInfo] from the marker window
 * and the camera ramp; this helper is only the coarse fallback for callers that just need
 * "a bit closer than the overview".
 */
internal fun followTileZoom(overviewZoom: Int): Int =
    (overviewZoom + 1).coerceIn(0, MAX_FOLLOW_ZOOM)

internal const val MAX_FOLLOW_ZOOM = 19
internal const val FOLLOW_GRID = 6
internal const val FOLLOW_SEGMENTS = 6

/** Short cross-fade at a prepared-window boundary, expressed in segment progress. */
internal const val FOLLOW_CROSSFADE = 0.12f

/** Sprint 41: Web-Mercator meters per pixel at zoom 0 on the equator. */
internal const val TILE_MPP0 = 156_543.03392

/** Sprint 41: target on-screen geo width during the follow (MOVE) phase, metres. */
internal const val FOLLOW_WINDOW_M = 2_000.0
/** Sprint 45: camera is 1.5x closer before every follow crop/zoom calculation. */
internal const val FOLLOW_ZOOM_MULTIPLIER = 1.5f

/**
 * Sprint 41: followBlend threshold for showing the sharp segment. The camera must
 * reach ~87% of its target zoom before the segment is swapped in, otherwise the
 * segment rectangle cannot cover the still-wide frame (see followCropInfo dz cap).
 */
internal const val FOLLOW_SWITCH = 0.85f

/** Adjacent follow-window centres are 45% apart, leaving geographic overlap for a seamless swap. */
internal const val FOLLOW_WINDOW_STEP_RATIO = 0.45


/** Sprint 43: per-tile retry attempts for the follow-camera prefetch. */
internal const val PREFETCH_TILE_RETRIES = 3
/** n = ceil(routeLength / (45% follow-window width)) + 1; centres overlap by design. */
internal fun adaptiveFollowSegmentCount(routeLengthMeters: Double, windowWidthMeters: Double): Int {
    val step = (windowWidthMeters * FOLLOW_WINDOW_STEP_RATIO).coerceAtLeast(1.0)
    return kotlin.math.ceil(routeLengthMeters / step).toInt() + 1
}

/** Web-Mercator meters per pixel at [zoom] for [lat]. */
internal fun metersPerPixel(lat: Double, zoom: Int): Double =
    TILE_MPP0 * cos(Math.toRadians(lat)) / (1 shl zoom)

/**
 * Static map backdrop for the video from cached OpenStreetMap raster tiles.
 * Plain PNG download + stitch, offline after the first export.
 * Null when offline with empty cache - renderer falls back to dark background.
 */
@Singleton
class MapSnapshotProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Video backdrop = Esri World Imagery satellite (free, no API key, attribution
        // required). Canonical MapServer path has NO file extension and the order is
        // z / y / x (not z/x/y)!
        private const val TILE_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d"
        /**
         * Sprint 65 (B1): the overview grid budget.
         *
         * Was 9 (3x3). That capped [pickZoom] one level below what the frame could show,
         * so the base layer was upscaled and the frame edges outside any follow segment
         * looked like magnified mush. 16 (4x4) buys roughly one more zoom level of real
         * Esri detail for 16 * 256 * 256 * 2 B = 2 MiB of RGB_565 - cheap, and it is the
         * layer that is visible on EVERY frame, including the intro and the outro.
         */
        const val MAX_TILES = 16
        const val TILE_PX = 256
        const val MAX_ZOOM = 16
        /** Minimum video window around the track (~1 km): keeps the crop sharp. */
        private const val MIN_SPAN_LON = 0.012
        private const val MIN_SPAN_LAT = 0.009

        /**
         * Exposes the exact zoom / tile grid / crop rectangle used by [snapshot] so
         * [RouteFrameRenderer] can align its overlay in the same coordinate space.
         * This keeps both classes coupled to one single source of truth instead of
         * duplicating the zoom-picking heuristic in two places.
         *
         * Static (companion) because the renderer has no provider instance to call on;
         * the crop calculation is pure and does not depend on the Context.
         */
        fun cropInfo(track: Track, width: Int, height: Int): CropInfo? {
            val points = track.points
            if (points.isEmpty()) return null
            val rawMinLat = points.minOf { it.lat }
            val rawMaxLat = points.maxOf { it.lat }
            val rawMinLon = points.minOf { it.lon }
            val rawMaxLon = points.maxOf { it.lon }
            // Sprint 16: guarantee a minimum geo window (~1 km) centred on the track.
            // A stand-still recording would otherwise crop a couple of pixels and
            // stretch them over the whole frame (blurry "mush"); this always shows
            // readable surroundings, like Relive does.
            val centerLat = (rawMinLat + rawMaxLat) / 2.0
            val centerLon = (rawMinLon + rawMaxLon) / 2.0
            val halfLat = max((rawMaxLat - rawMinLat) / 2.0, MIN_SPAN_LAT / 2.0)
            val halfLon = max((rawMaxLon - rawMinLon) / 2.0, MIN_SPAN_LON / 2.0)
            // Pad in Mercator and fit the output aspect BEFORE selecting the tile grid.
            // The overview contains both endpoints with room for marker rings and labels.
            val northY = fracY(centerLat + halfLat, 0)
            val southY = fracY(centerLat - halfLat, 0)
            val centerY = (northY + southY) / 2
            val aspect = width.toDouble() / height
            val spanY = max((southY - northY) * 1.5, halfLon * 2 / 360 * 1.5 / aspect)
            val spanX = spanY * aspect
            fun latitude(y: Double) = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * y))))
            val minLat = latitude((centerY + spanY / 2).coerceIn(0.0, 1.0))
            val maxLat = latitude((centerY - spanY / 2).coerceIn(0.0, 1.0))
            val minLon = (centerLon - spanX * 180).coerceIn(-180.0, 180.0)
            val maxLon = (centerLon + spanX * 180).coerceIn(-180.0, 180.0)
            val z = pickZoom(minLat, maxLat, minLon, maxLon, width, height)
            val x0 = tileX(minLon, z)
            val x1 = tileX(maxLon, z)
            val y0 = tileY(maxLat, z)
            val y1 = tileY(minLat, z)
            val tx = x1 - x0 + 1
            val ty = y1 - y0 + 1
            if (tx * ty <= 0 || tx * ty > MAX_TILES) return null
            val fullW = tx * TILE_PX
            val fullH = ty * TILE_PX
            val left = ((fracX(minLon, z) - x0) * TILE_PX).toInt().coerceIn(0, fullW - 1)
            val right = ((fracX(maxLon, z) - x0) * TILE_PX).toInt().coerceIn(left + 1, fullW)
            val top = ((fracY(maxLat, z) - y0) * TILE_PX).toInt().coerceIn(0, fullH - 1)
            val bottom = ((fracY(minLat, z) - y0) * TILE_PX).toInt().coerceIn(top + 1, fullH)
            return CropInfo(
                zoom = z,
                x0 = x0,
                y0 = y0,
                leftPx = left,
                topPx = top,
                cropW = right - left,
                cropH = bottom - top,
                fullW = fullW,
                fullH = fullH,
                minLat = minLat,
                maxLat = maxLat,
                minLon = minLon,
                maxLon = maxLon
            )
        }

        /**
         * Sprint 41: MOVE-phase camera zoom. The screen must show a fixed geo window
         * (~[FOLLOW_WINDOW_M]) around the marker regardless of the track bbox: short
         * rides keep the classic 4x, long rides zoom in until the window fits.
         */
        fun followCameraZoom(ov: CropInfo, zoomMultiplier: Float = FOLLOW_ZOOM_MULTIPLIER): Float {
            if (ov.cropW <= 0) return 4f * zoomMultiplier
            val midLat = (ov.minLat + ov.maxLat) / 2
            val geoW = ov.cropW * metersPerPixel(midLat, ov.zoom)
            return (geoW / FOLLOW_WINDOW_M * zoomMultiplier).toFloat().coerceIn(
                4f * zoomMultiplier,
                32f * zoomMultiplier
            )
        }

        /**
         * Sprint 41: where the follow segment sits INSIDE the overview frame.
         *
         * Both crops are Web-Mercator windows; dz = seg.zoom - ov.zoom >= 1, so one
         * segment pixel is 2^-dz overview pixels. With ovScale = output / ov.crop:
         *
         *   left = (segLeftWorld / 2^dz - ovLeftWorld) * ovScaleX   (top likewise)
         *   size = seg.crop * ovScale / 2^dz
         *
         * The renderer draws the segment into that rectangle in the SAME frame
         * coordinates as the overview, so switching segments can never move the
         * route/marker overlay. Returns left, top, right, bottom; null when the
         * geometry does not line up (caller then shows the overview only).
         */
        fun followRectInOverview(
            seg: CropInfo,
            ov: CropInfo,
            width: Int,
            height: Int
        ): FloatArray? {
            val dz = seg.zoom - ov.zoom
            if (dz < 1) return null
            if (ov.cropW <= 0 || ov.cropH <= 0 || seg.cropW <= 0 || seg.cropH <= 0) return null
            val tile = TILE_PX.toDouble()
            val factor = (1 shl dz).toDouble()
            val scaleX = width.toDouble() / ov.cropW
            val scaleY = height.toDouble() / ov.cropH
            val ovLeft = ov.x0 * tile + ov.leftPx
            val ovTop = ov.y0 * tile + ov.topPx
            val segLeft = (seg.x0 * tile + seg.leftPx) / factor
            val segTop = (seg.y0 * tile + seg.topPx) / factor
            val left = (segLeft - ovLeft) * scaleX
            val top = (segTop - ovTop) * scaleY
            return floatArrayOf(
                left.toFloat(),
                top.toFloat(),
                (left + seg.cropW * scaleX / factor).toFloat(),
                (top + seg.cropH * scaleY / factor).toFloat()
            )
        }

        /** True when [rect] covers the camera-transformed frame without edge gaps. */
        fun followRectCoversFrame(
            rect: FloatArray,
            centerX: Float,
            centerY: Float,
            cameraZoom: Float,
            width: Float,
            height: Float
        ): Boolean {
            if (rect.size < 4 || cameraZoom <= 0f) return false
            fun sourceX(screenX: Float) = centerX + (screenX - width / 2f) / cameraZoom
            fun sourceY(screenY: Float) = centerY + (screenY - height / 2f) / cameraZoom
            val left = sourceX(0f)
            val top = sourceY(0f)
            val right = sourceX(width)
            val bottom = sourceY(height)
            return left >= rect[0] && top >= rect[1] &&
                right <= rect[2] && bottom <= rect[3]
        }

        private fun pickZoom(
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
            w: Int, h: Int
        ): Int {
            // Sprint 16: pick the HIGHEST zoom whose tile grid still fits MAX_TILES.
            // Short tracks must zoom IN (z 14-16, 1-9 tiles), never fall back to z=1
            // (the whole world in one tile — unreadable). The crop itself is geo-exact,
            // so grid size only affects resolution, not correctness.
            for (z in MAX_ZOOM downTo 1) {
                val tx = tileX(maxLon, z) - tileX(minLon, z) + 1
                val ty = tileY(minLat, z) - tileY(maxLat, z) + 1
                if (tx * ty <= MAX_TILES) return z
            }
            return 1
        }

        private fun fracX(lon: Double, z: Int): Double =
            ((lon + 180.0) / 360.0) * (1 shl z)

        private fun fracY(lat: Double, z: Int): Double {
            val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0) * (1 shl z)
        }

        private fun tileX(lon: Double, z: Int): Int =
            floor(fracX(lon, z)).toInt().coerceIn(0, (1 shl z) - 1)

        private fun tileY(lat: Double, z: Int): Int =
            floor(fracY(lat, z)).toInt().coerceIn(0, (1 shl z) - 1)

        /**
         * Sprint 41: fixed 4x4 tile window centred on (lat, lon) - zoom from the
         * WINDOW AROUND THE MARKER, not from the whole-track overview bbox:
         *
         * 1. camZoom   = followCameraZoom(overview) (screen shows ~FOLLOW_WINDOW_M);
         * 2. mppTarget = screenGeoWidth * 0.9 / gridPx -> segment natives are ~10%
         *    finer than screen pixels (steady-state stretch ~0.9, was ~1.9 at 40A);
         * 3. dz        = round(log2(gridPx * camZoom * 1.0 / (ov.cropW * 0.9))),
         *    capped so the segment still covers the frame at the switch moment
         *    (camera ~87% of target, see FOLLOW_SWITCH) and z <= MAX_FOLLOW_ZOOM.
         */
        fun followCropInfo(
            lat: Double,
            lon: Double,
            overview: CropInfo,
            width: Int,
            height: Int
        ): CropInfo {
            val camZoom = followCameraZoom(overview)
            val midLat = (overview.minLat + overview.maxLat) / 2
            val screenGeoW =
                overview.cropW * metersPerPixel(midLat, overview.zoom) / camZoom
            val mppTarget = screenGeoW * 0.9 / (FOLLOW_GRID * TILE_PX)
            val mpp0 = TILE_MPP0 * cos(Math.toRadians(lat))
            val zDensity = ln(mpp0 / mppTarget) / ln(2.0)
            val camAtSwitch = 1f + FOLLOW_SWITCH * (camZoom - 1f)
            val dzCoverage =
                floor(ln(FOLLOW_GRID * TILE_PX * camAtSwitch * 0.95 / overview.cropW) / ln(2.0))
            var dz = Math.round(zDensity - overview.zoom).toInt()
            if (dz < 1) dz = 1
            val dzCap = min(MAX_FOLLOW_ZOOM - overview.zoom, floor(dzCoverage).toInt())
            if (dz > dzCap) dz = dzCap
            if (dz < 1) dz = 1
            val z = overview.zoom + dz
            val g = FOLLOW_GRID
            val cx = tileX(lon, z)
            val cy = tileY(lat, z)
            val half = g / 2
            val maxT = (1 shl z) - 1
            val x0 = (cx - half).coerceIn(0, (maxT - g + 1).coerceAtLeast(0))
            val y0 = (cy - half).coerceIn(0, (maxT - g + 1).coerceAtLeast(0))
            val fullW = g * TILE_PX
            val fullH = g * TILE_PX
            val fx = (fracX(lon, z) - x0) * TILE_PX
            val fy = (fracY(lat, z) - y0) * TILE_PX
            val aspect = width.toDouble() / height.coerceAtLeast(1)
            var cropW = fullW
            var cropH = (fullW / aspect).toInt().coerceIn(1, fullH)
            if (cropH >= fullH) {
                cropH = fullH
                cropW = (fullH * aspect).toInt().coerceIn(1, fullW)
            }
            val left = (fx - cropW / 2.0).toInt().coerceIn(0, fullW - cropW)
            val top = (fy - cropH / 2.0).toInt().coerceIn(0, fullH - cropH)
            fun latitude(y: Double) =
                Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * y))))
            val world = (1 shl z).toDouble() * TILE_PX
            val minLon = ((x0 * TILE_PX + left) / world * 360.0 - 180.0).coerceIn(-180.0, 180.0)
            val maxLon = ((x0 * TILE_PX + left + cropW) / world * 360.0 - 180.0).coerceIn(-180.0, 180.0)
            val maxLat = latitude(((y0 * TILE_PX + top) / world).coerceIn(0.0, 1.0))
            val minLat = latitude(((y0 * TILE_PX + top + cropH) / world).coerceIn(0.0, 1.0))
            return CropInfo(
                zoom = z,
                x0 = x0,
                y0 = y0,
                leftPx = left,
                topPx = top,
                cropW = cropW,
                cropH = cropH,
                fullW = fullW,
                fullH = fullH,
                minLat = minLat,
                maxLat = maxLat,
                minLon = minLon,
                maxLon = maxLon
            )
        }
    }

    /**
     * The exact zoom / tile grid / crop rectangle used by [snapshot] and
     * [RouteFrameRenderer.buildProjection].
     */
    data class CropInfo(
        val zoom: Int,
        val x0: Int,
        val y0: Int,
        val leftPx: Int,
        val topPx: Int,
        val cropW: Int,
        val cropH: Int,
        val fullW: Int,
        val fullH: Int,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    /**
     * Sprint 40A: follow-detail window around ONE marker position.
     * Real tiles at [zoom] centred on (lat, lon): no upscaling of the overview
     * mosaic. Same Esri z/y/x order and User-Agent as the overview path.
     */
    suspend fun followSnapshot(track: Track, progress: Float, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val pts = track.points
            if (pts.isEmpty()) return@withContext null
            val overview = cropInfo(track, width, height) ?: return@withContext null
            val pos = RouteVideoMotion(pts).position(progress.coerceIn(0f, 1f))
            val i = pos.toInt().coerceIn(0, pts.size - 1)
            val j = (i + 1).coerceAtMost(pts.size - 1)
            val t = (pos - i).coerceIn(0f, 1f)
            val lat = pts[i].lat + (pts[j].lat - pts[i].lat) * t
            val lon = pts[i].lon + (pts[j].lon - pts[i].lon) * t
            val crop = followCropInfo(lat, lon, overview, width, height)
            composeCrop(crop, "follow")
        }

    /** A crop plus its range of route progress. The renderer chooses by geographic containment. */
    data class FollowCrop(
        val info: CropInfo,
        val startProgress: Float,
        val endProgress: Float
    )

    data class FollowPrefetchReport(
        val crops: List<FollowCrop>,
        val expectedTiles: Int,
        val readyTiles: Int,
        val missingTiles: List<String>
    ) {
        val isComplete: Boolean get() = missingTiles.isEmpty() && readyTiles == expectedTiles
    }

    /**
     * Number of windows whose centres are at most half a follow-window width apart.
     * The hard cap fails instead of silently leaving large MOVE gaps.
     */

    /** Build the exact crop list once, shared by prefetch and cache-only stitching. */
    fun buildFollowCrops(track: Track, width: Int, height: Int): List<FollowCrop> {
        val pts = track.points
        if (pts.isEmpty()) return emptyList()
        val overview = cropInfo(track, width, height) ?: return emptyList()
        val motion = RouteVideoMotion(pts)
        val probe = followCropInfo(pts.first().lat, pts.first().lon, overview, width, height)
        val midLat = (overview.minLat + overview.maxLat) / 2.0
        val windowWidthM = probe.cropW * metersPerPixel(midLat, probe.zoom)
        val routeLengthM = RouteVideoMotion(pts).lengthMeters()
        val count = adaptiveFollowSegmentCount(routeLengthM, windowWidthM)
        if (count > MAX_FOLLOW_SEGMENTS) {
            throw IllegalStateException("follow needs $count segments, limit=$MAX_FOLLOW_SEGMENTS")
        }
        // The list runs over the WHOLE progress range, so the first and the last windows
        // are prepared exactly like every middle one. Sprint 66 kept this (it is only the
        // crop LIST; the renderer decides who may be drawn), but the sharp layer is again
        // gated by FOLLOW_SWITCH and excluded on FINISH (see RouteFrameRenderer). Windows
        // overlap by FOLLOW_WINDOW_STEP_RATIO, so handing over to the next one mid-move
        // never opens a gap.
        return (0 until count).map { k ->
            val start = k.toFloat() / count
            val end = (k + 1).toFloat() / count
            val progress = (start + end) / 2f
            val pos = motion.position(progress)
            val i = pos.toInt().coerceIn(0, pts.size - 1)
            val j = (i + 1).coerceAtMost(pts.size - 1)
            val t = (pos - i).coerceIn(0f, 1f)
            val lat = pts[i].lat + (pts[j].lat - pts[i].lat) * t
            val lon = pts[i].lon + (pts[j].lon - pts[i].lon) * t
            FollowCrop(followCropInfo(lat, lon, overview, width, height), start, end)
        }
    }

    /**
     * Fetches only missing tiles. Existing cache entries count as ready. Two rounds
     * cover transient failures; the returned report is the single encode gate.
     */
    suspend fun prefetchFollowTiles(
        track: Track,
        width: Int,
        height: Int
    ): FollowPrefetchReport = withContext(Dispatchers.IO) {
        val crops = buildFollowCrops(track, width, height)
        val unique = LinkedHashSet<String>()
        for (crop in crops) {
            for (index in 0 until FOLLOW_GRID * FOLLOW_GRID) {
                unique += "${crop.info.zoom}/${crop.info.x0 + index % FOLLOW_GRID}/${crop.info.y0 + index / FOLLOW_GRID}"
            }
        }
        val keys = unique.toList()
        val gate = Semaphore(4)
        suspend fun downloadMissing(): List<String> {
            val missing = keys.filter { TileDiskCache.load(cacheFileParts(it)) == null }
            coroutineScope {
                missing.map { key ->
                    async {
                        gate.withPermit {
                            val parts = key.split('/')
                            if (!currentCoroutineContext().isActive) return@withPermit
                            val z = parts[0].toInt(); val x = parts[1].toInt(); val y = parts[2].toInt()
                            val bitmap = downloadTileWithRetry(z, x, y) ?: return@withPermit
                            TileDiskCache.store(cacheFileParts(key), bitmap)
                            bitmap.recycle()
                        }
                    }
                }.awaitAll()
            }
            return keys.filter { TileDiskCache.load(cacheFileParts(it)) == null }
        }
        var missing = downloadMissing()
        if (missing.isNotEmpty()) missing = downloadMissing()
        FollowPrefetchReport(crops, keys.size, keys.size - missing.size, missing)
    }

    /** Cache-only stitch of the exact crop list produced by prefetch. */
    suspend fun followSnapshots(crops: List<FollowCrop>): List<FollowSegment> =
        withContext(Dispatchers.IO) {
            crops.map { FollowSegment(composeCropCached(it.info, "follow"), it.info) }
        }

    private suspend fun composeCropCached(crop: CropInfo, kind: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val zoom = crop.zoom
            val x0 = crop.x0
            val y0 = crop.y0
            val fullW = crop.fullW
            val fullH = crop.fullH
            val full = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.RGB_565)
            val canvas = Canvas(full)
            val tileCountX = fullW / TILE_PX
            val tileCountY = fullH / TILE_PX
            val gate = Semaphore(4)
            val tiles = coroutineScope {
                (0 until tileCountX * tileCountY).map { index ->
                    async {
                        gate.withPermit {
                            if (!currentCoroutineContext().isActive) null
                            else loadTileCached(
                                zoom, x0 + index % tileCountX, y0 + index / tileCountX
                            )
                        }
                    }
                }.awaitAll()
            }
            finishCrop(full, canvas, tiles, tileCountX, crop, kind)
        }

    private fun finishCrop(
        full: Bitmap,
        canvas: Canvas,
        tiles: List<Bitmap?>,
        tileCountX: Int,
        crop: CropInfo,
        kind: String
    ): Bitmap? {
        val missingPaint = Paint().apply { color = Color.rgb(58, 63, 70) }
        var loaded = 0
        for ((index, tile) in tiles.withIndex()) {
            val dx = ((index % tileCountX) * TILE_PX).toFloat()
            val dy = ((index / tileCountX) * TILE_PX).toFloat()
            if (tile == null) {
                canvas.drawRect(dx, dy, dx + TILE_PX, dy + TILE_PX, missingPaint)
            } else {
                canvas.drawBitmap(tile, dx, dy, null)
                tile.recycle()
                loaded++
            }
        }
        if (loaded != tiles.size) {
            Log.i("MapSnapshot", "$kind null: tiles=$loaded/${tiles.size} -> strict follow gate")
            full.recycle()
            return null
        }
        val result = runCatching {
            val cropped = Bitmap.createBitmap(full, crop.leftPx, crop.topPx, crop.cropW, crop.cropH)
            val scaled = Bitmap.createScaledBitmap(cropped, crop.cropW, crop.cropH, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        }.getOrNull()
        full.recycle()
        return result
    }

    private suspend fun composeCrop(crop: CropInfo, kind: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val zoom = crop.zoom
            val x0 = crop.x0
            val y0 = crop.y0
            val fullW = crop.fullW
            val fullH = crop.fullH
            val full = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.RGB_565)
            val canvas = Canvas(full)
            val tileCountX = fullW / TILE_PX
            val tileCountY = fullH / TILE_PX
            val gate = Semaphore(4)
            val tiles = coroutineScope {
                (0 until tileCountX * tileCountY).map { index ->
                    async {
                        gate.withPermit {
                            if (!currentCoroutineContext().isActive) null
                            else {
                                val x = x0 + index % tileCountX
                                val y = y0 + index / tileCountX
                                loadTile(zoom, x, y)
                            }
                        }
                    }
                }.awaitAll()
            }
            val missingPaint = Paint().apply { color = Color.rgb(58, 63, 70) }
            var loaded = 0
            for ((index, tile) in tiles.withIndex()) {
                val dx = ((index % tileCountX) * TILE_PX).toFloat()
                val dy = ((index / tileCountX) * TILE_PX).toFloat()
                if (tile == null) {
                    canvas.drawRect(dx, dy, dx + TILE_PX, dy + TILE_PX, missingPaint)
                } else {
                    canvas.drawBitmap(tile, dx, dy, null)
                    tile.recycle()
                    loaded++
                }
            }
            if (loaded * 2 < tiles.size) {
                Log.i("MapSnapshot", "$kind null: sat tiles=$loaded/${tiles.size} -> schematic")
                full.recycle()
                return@withContext null
            }
            val result = runCatching {
                val cropped = Bitmap.createBitmap(full, crop.leftPx, crop.topPx, crop.cropW, crop.cropH)
                val scaled = Bitmap.createScaledBitmap(cropped, crop.cropW, crop.cropH, true)
                if (scaled !== cropped) cropped.recycle()
                scaled
            }.getOrNull()
            full.recycle()
            result
        }

    suspend fun snapshot(track: Track, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val crop = cropInfo(track, width, height) ?: run {
                Log.i("MapSnapshot", "snapshot null: no usable crop")
                return@withContext null
            }
            val zoom = crop.zoom
            val x0 = crop.x0
            val y0 = crop.y0
            val fullW = crop.fullW
            val fullH = crop.fullH
            Log.i("MapSnapshot", "snapshot z=$zoom grid=${fullW / TILE_PX}x${fullH / TILE_PX}")
            val full = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.RGB_565)
            val canvas = Canvas(full)
            val tileCountX = fullW / TILE_PX
            val tileCountY = fullH / TILE_PX
            val cells = ArrayList<Pair<Int, Int>>(tileCountX * tileCountY)
            // Row-major, matching index % tileCountX and index / tileCountX below.
            for (y in y0 until y0 + tileCountY) {
                for (x in x0 until x0 + tileCountX) cells.add(x to y)
            }
            // Parallel tile loading (max 4 connections); one failed tile becomes a grey
            // cell instead of discarding the whole backdrop.
            val gate = Semaphore(4)
            val tiles = coroutineScope {
                cells.map { (x, y) ->
                    async {
                        gate.withPermit {
                            if (!currentCoroutineContext().isActive) null else x to loadTile(zoom, x, y)
                        }
                    }
                }.awaitAll()
            }
            val missingPaint = Paint().apply { color = Color.rgb(58, 63, 70) }
            var loaded = 0
            for ((index, entry) in tiles.withIndex()) {
                if (entry == null) continue
                val (_, tile) = entry
                val dx = ((index % tileCountX) * TILE_PX).toFloat()
                val dy = ((index / tileCountX) * TILE_PX).toFloat()
                if (tile == null) {
                    canvas.drawRect(dx, dy, dx + TILE_PX, dy + TILE_PX, missingPaint)
                } else {
                    canvas.drawBitmap(tile, dx, dy, null)
                    tile.recycle()
                    loaded++
                }
            }
            // Sprint 22: a mostly-grey satellite mosaic is worse than no satellite.
            // Fall back to cached/downloaded OSM live tiles (the imagery the user sees on
            // screen), and if even that fails — let the renderer draw its dark schematic
            // backdrop; never a plain grey rectangle.
            var backgroundKind = "sat"
            if (loaded * 2 < tiles.size) {
                var liveLoaded = 0
                for ((index, cell) in cells.withIndex()) {
                    val (x, y) = cell
                    val tile = loadLiveTile(zoom, x, y) ?: continue
                    val dx = ((index % tileCountX) * TILE_PX).toFloat()
                    val dy = ((index / tileCountX) * TILE_PX).toFloat()
                    canvas.drawBitmap(tile, dx, dy, null)
                    tile.recycle()
                    liveLoaded++
                }
                if (liveLoaded * 2 >= tiles.size) {
                    backgroundKind = "live_cache"
                } else {
                    Log.i("MapSnapshot", "snapshot null: sat tiles=$loaded/${tiles.size}, live=$liveLoaded -> schematic")
                    full.recycle()
                    return@withContext null
                }
            }
            Log.i("MapSnapshot", "background=$backgroundKind tiles=$loaded/${tiles.size}")
            // Crop exact geo window, then scale once to the video frame.
            val result = runCatching {
                val cropped = Bitmap.createBitmap(full, crop.leftPx, crop.topPx, crop.cropW, crop.cropH)
                val scaled = Bitmap.createScaledBitmap(cropped, width, height, true)
                if (scaled !== cropped) cropped.recycle()
                scaled
            }.getOrNull()
            full.recycle()
            result
        }

    private fun cacheFile(z: Int, x: Int, y: Int): File =
        // Separate namespace from the live-map tiles (different imagery source).
        TileDiskCache.file(context, "sat", z, x, y)

    private fun cacheFileParts(key: String): File {
        val p = key.split('/')
        return cacheFile(p[0].toInt(), p[1].toInt(), p[2].toInt())
    }

    private fun liveCacheFile(z: Int, x: Int, y: Int): File =
        TileDiskCache.file(context, "live2", z, x, y)

    /**
     * OSM fallback backdrop: same tile grid as the satellite request, but the live OSM
     * imagery (disk cache first, network second). Used when Esri is unreachable so the
     * video never degrades to a plain grey rectangle.
     */
    private fun loadLiveTile(z: Int, x: Int, y: Int): Bitmap? {
        val file = liveCacheFile(z, x, y)
        TileDiskCache.load(file)?.let { return it }
        val bitmap = runCatching {
            val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 2_500
                connection.readTimeout = 2_500
                connection.setRequestProperty("User-Agent", MapTiles.USER_AGENT)
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null
        TileDiskCache.store(file, bitmap)
        return bitmap
    }

    /**
     * Sprint 40: cache-only tile read. The encode loop must never hit the network:
     * prefetch happens before encoding; a missing tile is a grey cell, never drift.
     */
    private fun loadTileCached(z: Int, x: Int, y: Int): Bitmap? =
        TileDiskCache.load(cacheFile(z, x, y))

    /** Sprint 40: follow stitch bound to the GIVEN crop (no recompute from marker). */
    data class FollowSegment(
        val bitmap: Bitmap?,
        val info: CropInfo
    )

    private fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val file = cacheFile(z, x, y)
        TileDiskCache.load(file)?.let { return it }
        val bitmap = downloadTile(z, x, y) ?: return null
        TileDiskCache.store(file, bitmap)
        return bitmap
    }

    private fun downloadTile(z: Int, x: Int, y: Int): Bitmap? = runCatching {
        val url = URL(String.format(TILE_URL, z, y, x))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.setRequestProperty("User-Agent", MapTiles.USER_AGENT)
            connection.connect()
            val code = connection.responseCode
            val contentType = connection.contentType ?: ""
            // Tile grid only (z/x/y), never track coordinates.
            if (code != HttpURLConnection.HTTP_OK || !contentType.startsWith("image/")) {
                Log.i("MapSnapshot", "tile z=$z HTTP $code ct=$contentType -> skipped (not an image)")
                return null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Log.i(
                "MapSnapshot",
                "tile z=$z HTTP $code ct=$contentType bytes=${bytes.size} decode=${bitmap != null}"
            )
            bitmap
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Sprint 43: per-tile retry wrapper for the follow-camera prefetch. A transient
     * 5xx / connection drop doesn't leave a grey cell; the caller's wall-clock
     * timeout bounds the extra attempts.
     */
    private suspend fun downloadTileWithRetry(z: Int, x: Int, y: Int): Bitmap? {
        for (attempt in 1..PREFETCH_TILE_RETRIES) {
            val bmp = downloadTile(z, x, y)
            if (bmp != null) return bmp
            if (attempt < PREFETCH_TILE_RETRIES) {
                val backoffMs = when (attempt) {
                    1 -> 500L
                    else -> 1500L
                }
                Log.i("MapSnapshot", "tile z=$z x=$x y=$y prefetch attempt $attempt failed, retry in ${backoffMs}ms")
                delay(backoffMs)
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) return null
            }
        }
        Log.i("MapSnapshot", "tile z=$z x=$x y=$y prefetch exhausted $PREFETCH_TILE_RETRIES attempts")
        return null
    }
}

