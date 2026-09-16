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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

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
        const val MAX_TILES = 9
        const val TILE_PX = 256
        const val MAX_ZOOM = 16

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
            if (points.size < 2) return null
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
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

    suspend fun snapshot(track: Track, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val crop = MapSnapshotProvider.cropInfo(track, width, height) ?: run {
                Log.i("MapSnapshot", "snapshot null: no crop (points<2 or span exceeds MAX_TILES=$MAX_TILES)")
                return@withContext null
            }
            Log.i(
                "MapSnapshot",
                "snapshot bounds=(%.2f,%.2f)-(%.2f,%.2f) z=%d grid=%dx%d".format(
                    crop.minLat, crop.minLon, crop.maxLat, crop.maxLon, crop.zoom,
                    crop.fullW / TILE_PX, crop.fullH / TILE_PX
                )
            )
            val fullW = crop.fullW
            val fullH = crop.fullH
            val full = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.RGB_565)
            val canvas = Canvas(full)
            val tileCountX = fullW / TILE_PX
            val tileCountY = fullH / TILE_PX
            val cells = ArrayList<Pair<Int, Int>>(tileCountX * tileCountY)
            for (x in crop.x0 until crop.x0 + tileCountX) {
                for (y in crop.y0 until crop.y0 + tileCountY) cells.add(x to y)
            }
            // Parallel tile loading (max 4 connections); one failed tile becomes a grey
            // cell instead of discarding the whole backdrop.
            val gate = Semaphore(4)
            val tiles = coroutineScope {
                cells.map { (x, y) ->
                    async {
                        gate.withPermit {
                            if (!currentCoroutineContext().isActive) null else x to loadTile(crop.zoom, x, y)
                        }
                    }
                }.awaitAll()
            }
            val missingPaint = Paint().apply { color = Color.rgb(58, 63, 70) }
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
                }
            }
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
        File(context.filesDir, "map_tiles/sat/$z/$x/$y.png")

    private fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val file = cacheFile(z, x, y)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        val bitmap = downloadTile(z, x, y) ?: return null
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
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
}

