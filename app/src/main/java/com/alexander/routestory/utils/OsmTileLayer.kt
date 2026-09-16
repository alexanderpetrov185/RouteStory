package com.alexander.routestory.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * Live-map fallback without MapLibre: downloads a small mosaic of OSM raster tiles
 * around the current route/position and returns it as one bitmap, so the Canvas
 * renderer can show real map imagery even when the native renderer is dead.
 * Shares the on-disk tile cache (filesDir/map_tiles/z/x/y.png) with the video snapshot.
 */
object OsmTileLayer {
    const val TILE_PX = 256

    /** Tile mosaic anchored at tile (x0, y0); bitmap is tiles*256 px in each axis. */
    data class Mosaic(
        val bitmap: Bitmap,
        val zoom: Int,
        val x0: Int,
        val y0: Int,
        val tilesX: Int,
        val tilesY: Int
    ) {
        val widthPx: Int get() = tilesX * TILE_PX
        val heightPx: Int get() = tilesY * TILE_PX
    }

    /** Largest zoom whose (tiles-1) x (tiles-1) grid still covers the given span. */
    fun pickZoom(centerLat: Double, lonSpanDeg: Double, latSpanDeg: Double, tiles: Int): Int {
        // Mercator stretches Y by 1/cos(lat); keep both spans in Mercator degrees.
        val latStretch = 1.0 / max(0.2, cos(Math.toRadians(centerLat)))
        val spanMerc = max(lonSpanDeg, latSpanDeg * latStretch)
        // Cap at 14: street-level 15-16 is visually noisy and costs extra tile downloads;
        // 13-14 is enough for a live city map.
        for (z in 14 downTo 4) {
            if (spanMerc <= 360.0 / (1 shl z) * (tiles - 1) * 0.9) return z
        }
        return 4
    }

    private fun mercYTil(lat: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0
    }

    /** Mercator Y tile fraction, exposed for the renderer's projection. */
    fun mercYTiles(lat: Double): Double = mercYTil(lat)

    /**
     * Downloads a tiles x tiles OSM mosaic centred on (centerLat, centerLon).
     * Failed tiles are painted grey; null only when every tile failed.
     */
    suspend fun mosaic(
        context: Context,
        centerLat: Double,
        centerLon: Double,
        lonSpanDeg: Double,
        latSpanDeg: Double,
        tiles: Int = 3
    ): Mosaic? = withContext(Dispatchers.IO) {
        val zoom = pickZoom(centerLat, lonSpanDeg, latSpanDeg, tiles)
        val n = 1 shl zoom
        val cx = floor(((centerLon + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
        val cy = floor(mercYTil(centerLat) * n).toInt().coerceIn(0, n - 1)
        val x0 = (cx - tiles / 2).coerceIn(0, n - tiles)
        val y0 = (cy - tiles / 2).coerceIn(0, n - tiles)
        val full = Bitmap.createBitmap(tiles * TILE_PX, tiles * TILE_PX, Bitmap.Config.RGB_565)
        val canvas = Canvas(full)
        val missingPaint = Paint().apply { color = Color.rgb(58, 63, 70) }

        val gate = Semaphore(4)
        val results = coroutineScope {
            val cells = ArrayList<Pair<Int, Int>>(tiles * tiles)
            for (x in x0 until x0 + tiles) for (y in y0 until y0 + tiles) cells.add(x to y)
            cells.map { (x, y) ->
                async {
                    gate.withPermit {
                        Triple(x, y, loadTile(context, zoom, x, y))
                    }
                }
            }.awaitAll()
        }
        var ok = 0
        for ((x, y, tile) in results) {
            val dx = ((x - x0) * TILE_PX).toFloat()
            val dy = ((y - y0) * TILE_PX).toFloat()
            if (tile == null) {
                canvas.drawRect(dx, dy, dx + TILE_PX, dy + TILE_PX, missingPaint)
            } else {
                canvas.drawBitmap(tile, dx, dy, null)
                tile.recycle()
                ok++
            }
        }
        if (ok == 0) {
            full.recycle()
            null
        } else {
            Log.i("MapSnapshot", "live mosaic z=$zoom ok=$ok/${tiles * tiles}")
            Mosaic(full, zoom, x0, y0, tiles, tiles)
        }
    }

    private fun cacheFile(context: Context, z: Int, x: Int, y: Int): File =
        // v2: bump after the "API KEY REQUIRED" Carto placeholder tiles may have been
        // cached by older builds, so they can never be served again.
        File(context.filesDir, "map_tiles/live2/$z/$x/$y.png")

    private fun loadTile(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        val file = cacheFile(context, z, x, y)
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
        val url = URL(String.format(MapTiles.liveTileUrlTemplate(), z, x, y))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2_500
            conn.readTimeout = 2_500
            conn.setRequestProperty("User-Agent", MapTiles.USER_AGENT)
            conn.connect()
            val code = conn.responseCode
            // Tile grid only, never track coordinates.
            Log.i("MapSnapshot", "live tile z=$z HTTP $code")
            if (code != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
