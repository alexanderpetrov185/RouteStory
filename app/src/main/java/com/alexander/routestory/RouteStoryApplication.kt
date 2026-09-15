package com.alexander.routestory

import android.app.Application
import com.alexander.routestory.utils.MapAvailability
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre

@HiltAndroidApp
class RouteStoryApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Detects whether the previous process died while the map was initialising, so the
        // map can be replaced by the canvas renderer instead of crashing again.
        runCatching { MapAvailability.onProcessStart(this) }
        // MapLibre must be initialised before any MapView is created. A failure here
        // must never take the app down: the map screen falls back to the canvas renderer.
        runCatching { MapLibre.getInstance(this) }
    }
}