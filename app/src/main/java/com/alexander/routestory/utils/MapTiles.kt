package com.alexander.routestory.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Map configuration. Uses OSM raster tiles bundled as a MapLibre style in assets.
 * No API key required.
 */
object MapTiles {
    /** Zoom level used while the camera follows the live position. */
    const val TRACKING_ZOOM = 16.0

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
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        }.getOrDefault(false)
        cachedReachable = reachable
        return reachable
    }
}
