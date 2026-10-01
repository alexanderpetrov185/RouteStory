package com.alexander.routestory.utils

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Sprint 61: single GPS-fix helper shared by Tracking and RouteBuilder.
 * Throws SecurityException when runtime permissions are missing,
 * so callers must check permissions / GPS state first and surface the status in UI.
 *
 * Sprint 62: the cached [lastLocation] is always tried FIRST - it answers in milliseconds and is
 * what makes the map land on the real place immediately. [forceFresh] only means "also ask for a
 * better fix", it never skips the cache: on a cold GPS start `getCurrentLocation` can hang for
 * tens of seconds, which is exactly why the builder used to show the tile fallback ("Moscow")
 * instead of the user. The fresh request is additionally bounded by [FRESH_FIX_TIMEOUT_MS].
 */
@Singleton
class LocationFixProvider @Inject constructor() {

    @SuppressLint("MissingPermission")
    suspend fun requestFix(context: Context, forceFresh: Boolean): Pair<Double, Double>? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cached = awaitLastLocation(client)
        if (cached != null && !forceFresh) return cached
        // Sprint 62: a fresh fix must never block the screen. The cached fix already answers in
        // milliseconds, so it is always tried first; the fresh one only refines it.
        val fresh = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { awaitCurrentLocation(client) }
        return fresh ?: cached
    }

    private suspend fun awaitLastLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient
    ): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        runCatching {
            client.lastLocation
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(
        client: com.google.android.gms.location.FusedLocationProviderClient
    ): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    companion object {
        /** Hard ceiling for the high-accuracy request; on timeout the cached fix wins. */
        const val FRESH_FIX_TIMEOUT_MS = 6_000L
    }
}
