package com.alexander.routestory.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.URL

/**
 * Map configuration. Uses OSM raster tiles bundled as a MapLibre style in assets.
 * No API key required.
 */
object MapTiles {
    /** Zoom level used while the camera follows the live position. */
    const val TRACKING_ZOOM = 16.0

    /** OSM raster tiles — the guaranteed free fallback for the live map. */
    const val OSM_TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"

    /** CARTO dark raster: muted dark style, better fit for a tracking screen than
     *  the colourful OSM default. Requires no key on some deployments — probed at
     *  runtime, and OSM is used whenever the probe does not return an image. */
    private const val DARK_TILE_URL = "https://basemaps.cartocdn.com/dark_all/%d/%d/%d.png"

    /**
     * Sprint 12 (MVP decision): MapLibre on the test device stayed blank despite a
     * healthy network (probe HTTP 200), so the live map is always rendered through the
     * proven OSM-tile Canvas path (OsmTileLayer). MapLibre remains in the code but is
     * not selected while this flag is on.
     */
    const val FORCE_OSM_CANVAS = true

    /**
     * Identifying User-Agent required by the OSM Tile Usage Policy for every tile request
     * (live map via MapLibre, availability probe and video snapshot alike).
     */
    const val USER_AGENT = "RouteStory/0.5.0 (Android; https://github.com/alexanderpetrov185/RouteStory)"

    /** True after [liveTileUrlTemplate] has chosen the dark style. */
    @Volatile
    private var liveTileUrl: String? = null

    /**
     * Live-map tile URL template: CARTO dark if a probe tile returns an image (the
     * service is intermittently key-gated), otherwise plain OSM. Result cached for
     * the process lifetime. Only the probe result is logged — never user coordinates.
     */
    fun liveTileUrlTemplate(): String {
        liveTileUrl?.let { return it }
        val probeUrl = "https://basemaps.cartocdn.com/dark_all/10/548/335.png"
        val darkOk = runCatching {
            val conn = URL(probeUrl).openConnection() as java.net.HttpURLConnection
            try {
                conn.connectTimeout = 2_500
                conn.readTimeout = 2_500
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connect()
                val code = conn.responseCode
                val contentType = conn.contentType ?: ""
                Log.i("MapTiles", "dark_all probe HTTP $code content-type=$contentType")
                code == 200 && contentType.startsWith("image/")
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
        val chosen = if (darkOk) DARK_TILE_URL else OSM_TILE_URL
        Log.i("MapTiles", "live tiles -> ${if (darkOk) "carto dark_all" else "osm"}")
        liveTileUrl = chosen
        return chosen
    }

    /**
     * Loads the bundled OSM raster style JSON from assets.
     * Returns null when the asset is missing (defensive, should not happen).
     */
    fun loadStyleJson(context: Context): String? = runCatching {
        context.assets.open("route_map_style.json").bufferedReader().use { it.readText() }
    }.getOrNull()

    /**
     * True when tiles could be downloaded right now. ConnectivityManager alone is not
     * sufficient (a connected-but-unusable radio reports NET_CAPABILITY_INTERNET while
     * tile.openstreetmap.org stays unreachable), so the flag is a hint only; the real
     * availability is checked by [canReachTileServer]. Never throws.
     */
    fun isOnline(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /**
     * Real check: HEAD request to a tile server. ConnectivityManager can report "online"
     * while the emulator has no usable path to tile.openstreetmap.org, and vice versa.
     * The result is cached for the whole process lifetime: the probe costs up to 6 s and
     * must not run on every screen visit.
     */
    @Volatile
    private var cachedReachable: Boolean? = null

    fun canReachTileServer(): Boolean {
        cachedReachable?.let { return it }
        val reachable = runCatching {
            val url = java.net.URL("https://tile.openstreetmap.org/0/0/0.png")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()
            val code = conn.responseCode
            val len = if (code in 200..299) {
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                    total
                }
            } else 0
            conn.disconnect()
            Log.i("MapTiles", "tile probe HTTP $code bytes=$len")
            code == 200 && len > 1000
        }.getOrDefault(false).also {
            Log.i("MapTiles", "canReachTileServer=$it")
        }
        cachedReachable = reachable
        return reachable
    }
}
