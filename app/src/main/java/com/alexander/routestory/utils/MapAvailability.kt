package com.alexander.routestory.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.alexander.routestory.R

/**
 * Decides whether the MapLibre view may be created at all.
 *
 * A native crash inside the map renderer cannot be caught from Kotlin, so the only reliable
 * protection is to never start the renderer in a known-risky situation (emulated GPU) and to
 * remember a crash that happened while the map was initialising.
 */
object MapAvailability {

    private const val PREFS = "map_state"
    private const val KEY_ATTEMPT = "init_attempt"
    private const val KEY_CRASHES = "init_crashes"
    private const val MAX_CRASHES = 2

    @Volatile
    private var enabledThisSession: Boolean? = null

    /** Called once from Application.onCreate, before any UI exists. */
    fun onProcessStart(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_ATTEMPT, false)) {
            // The previous process died while the map was being created: count it and give up
            // on the map for a while instead of crashing again on every launch.
            prefs.edit()
                .putBoolean(KEY_ATTEMPT, false)
                .putInt(KEY_CRASHES, prefs.getInt(KEY_CRASHES, 0) + 1)
                .commit()
        }
        enabledThisSession = computeEnabled(context, prefs)
    }

    /** True when the native map may be created in this process. */
    fun canUseMap(context: Context): Boolean {
        enabledThisSession?.let { return it }
        val allowed = computeEnabled(context, prefs(context))
        enabledThisSession = allowed
        return allowed
    }

    /** Breadcrumb written right before the map is created. Must survive an immediate crash. */
    fun markAttemptStarted(context: Context) {
        prefs(context).edit().putBoolean(KEY_ATTEMPT, true).commit()
    }

    /** The style has loaded, so map initialisation is over; forget earlier failures. */
    fun markReady(context: Context) {
        prefs(context).edit().putBoolean(KEY_ATTEMPT, false).putInt(KEY_CRASHES, 0).commit()
    }

    private fun computeEnabled(context: Context, prefs: SharedPreferences): Boolean {
        if (prefs.getInt(KEY_CRASHES, 0) >= MAX_CRASHES) return false
        val allowedOnEmulator = context.resources.getBoolean(R.bool.map_use_on_emulator)
        if (isEmulator() && !allowedOnEmulator) return false
        return true
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Emulated GPUs are the main source of native crashes in GL map renderers. */
    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("sdk") ||
            model.contains("emulator") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("vbox")
    }
}
