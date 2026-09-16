package com.alexander.routestory

import android.app.Application
import com.alexander.routestory.utils.MapAvailability
import com.alexander.routestory.utils.MapTiles
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

@HiltAndroidApp
class RouteStoryApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Detects whether the previous process died while the map was initialising, so the
        // map can be replaced by the canvas renderer instead of crashing again.
        runCatching { MapAvailability.onProcessStart(this) }
        // OSM Tile Usage Policy: every tile request must carry an identifying User-Agent.
        // Must be configured before MapLibre.getInstance so the very first style/tile
        // requests already use it.
        runCatching {
            HttpRequestUtil.setOkHttpClient(
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", MapTiles.USER_AGENT)
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            )
        }
        // MapLibre must be initialised before any MapView is created. A failure here
        // must never take the app down: the map screen falls back to the canvas renderer.
        runCatching { MapLibre.getInstance(this) }
    }
}