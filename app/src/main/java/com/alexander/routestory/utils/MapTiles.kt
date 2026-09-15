package com.alexander.routestory.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Map configuration. OpenFreeMap serves OpenStreetMap based vector tiles and needs no API key,
 * which keeps the map usable without any registration.
 */
object MapTiles {
    /** Vector style used for the map. Needs the network on first load; MapLibre caches it after. */
    const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

    /** Zoom level used while the camera follows the live position. */
    const val TRACKING_ZOOM = 16.0

    /** True when tiles could be downloaded right now. Never throws. */
    fun isOnline(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
