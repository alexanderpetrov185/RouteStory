package com.alexander.routestory

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre

@HiltAndroidApp
class RouteStoryApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // MapLibre must be initialised before any MapView is created. A failure here
        // must never take the app down: the map screen falls back to the canvas renderer.
        runCatching { MapLibre.getInstance(this) }
    }
}