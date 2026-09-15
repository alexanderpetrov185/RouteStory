package com.alexander.routestory.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
 * Static map backdrop for the video from cached OpenFreeMap raster tiles.
 * Plain PNG download + stitch, offline after the first export.
 * Null when offline with empty cache - renderer falls back to dark background.
 */
@Singleton
class MapSnapshotProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TILE_URL = "https://tiles.openfreemap.org/planet/%d/%d/%d.png"
        private const val MAX_TILES = 24
        private const val TILE_PX = 256
        private const val MAX_ZOOM = 14
    }

    suspend fun snapshot(track: com.alexander.routestory.domain.Track, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val points = track.points
            if (points.size < 2) return@withContext null
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val zoom = pickZoom(minLat, maxLat, minLon, maxLon, width, height)
            val x0 = tileX(minLon, zoom)
            val x1 = tileX(maxLon, zoom)
            val y0 = tileY(maxLat, zoom)
            val y1 = tileY(minLat, zoom)
            val count = (x1 - x0 + 1) * (y1 - y0 + 1)
            if (count <= 0 || count > MAX_TILES) return@withContext null
            val fullW = (x1 - x0 + 1) * TILE_PX
            val fullH = (y1 - y0 + 1) * TILE_PX
            val full = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.RGB_565)
            val canvas = Canvas(full)
            for (x in x0..x1) {
                for (y in y0..y1) {
                    if (!currentCoroutineContext().isActive) {
                        full.recycle()
                        return@withContext null
                    }
                    val tile = loadTile(zoom, x, y)
                    if (tile == null) {
                        full.recycle()
                        return@withContext null
                    }
                    canvas.drawBitmap(tile, ((x - x0) * TILE_PX).toFloat(), ((y - y0) * TILE_PX).toFloat(), null)
                    tile.recycle()
                }
            }
            // Crop exact geo window, then scale once to the video frame.
            val left = ((fracX(minLon, zoom) - x0) * TILE_PX).toInt().coerceIn(0, fullW - 1)
            val right = ((fracX(maxLon, zoom) - x0) * TILE_PX).toInt().coerceIn(left + 1, fullW)
            val top = ((fracY(maxLat, zoom) - y0) * TILE_PX).toInt().coerceIn(0, fullH - 1)
            val bottom = ((fracY(minLat, zoom) - y0) * TILE_PX).toInt().coerceIn(top + 1, fullH)
            val result = runCatching {
                val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                val scaled = Bitmap.createScaledBitmap(cropped, width, height, true)
                if (scaled !== cropped) cropped.recycle()
                scaled
            }.getOrNull()
            full.recycle()
            result
        }

    private fun cacheFile(z: Int, x: Int, y: Int): File =
        File(context.filesDir, "map_tiles/$z/$x/$y.png")

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
        val url = URL(String.format(TILE_URL, z, x, y))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", "RouteStory/1.0 (Android)")
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun pickZoom(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, w: Int, h: Int): Int {
        for (z in MAX_ZOOM downTo 1) {
            val tx = tileX(maxLon, z) - tileX(minLon, z) + 1
            val ty = tileY(minLat, z) - tileY(maxLat, z) + 1
            if (tx * ty <= MAX_TILES && tx * TILE_PX >= w / 2 && ty * TILE_PX >= h / 2) return z
        }
        return 1
    }

    private fun fracX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
    private fun fracY(lat: Double, z: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * (1 shl z)
    }
    private fun tileX(lon: Double, z: Int): Int = floor(fracX(lon, z)).toInt().coerceIn(0, (1 shl z) - 1)
    private fun tileY(lat: Double, z: Int): Int = floor(fracY(lat, z)).toInt().coerceIn(0, (1 shl z) - 1)
}
