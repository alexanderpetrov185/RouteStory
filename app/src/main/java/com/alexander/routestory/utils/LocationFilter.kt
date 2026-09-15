package com.alexander.routestory.utils

import com.alexander.routestory.domain.TrackPoint

/** Filters out inaccurate, jittery and too-close GPS points. */
object LocationFilter {
    private const val MAX_ACCURACY_M = 30f
    private const val MAX_SPEED_MPS = 60f
    private const val MIN_DISTANCE_M = 3f

    fun shouldAccept(previous: TrackPoint?, candidate: TrackPoint): Boolean {
        if (candidate.accuracy > MAX_ACCURACY_M) return false
        if (previous == null) return true
        val distance = GeoUtils.distanceMeters(previous, candidate)
        val dtSec = (candidate.timestamp - previous.timestamp) / 1000f
        if (dtSec > 0f && distance / dtSec > MAX_SPEED_MPS) return false
        if (distance < MIN_DISTANCE_M) return false
        return true
    }
}