package com.alexander.routestory.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Map configuration. The style sheet lives in assets and is the single place to swap
 * tile providers later (e.g. a vector style instead of the OSM raster tiles).
 */
object MapTiles {
    const val STYLE_ASSET = "route_map_style.json"

    /** Zoom level used while the camera follows the live position. */
    const val TRACKING_ZOOM = 16.0

    /** Reads the bundled style; a missing asset disables the map and keeps the canvas renderer. */
    fun loadStyleJson(context: Context): String? = runCatching {
        context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()

    /** True when tiles could be downloaded right now. Never throws. */
    fun isOnline(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
