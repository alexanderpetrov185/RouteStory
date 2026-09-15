package com.alexander.routestory.utils

import com.alexander.routestory.domain.TrackPoint

/**
 * Filters out inaccurate and physically impossible GPS points.
 * Distance accumulation uses [isSignificantMove] so that GPS jitter is not counted as travel.
 */
object LocationFilter {
    /** Outdoor accuracy is usually 5-20 m; indoor and emulator fixes can be much worse. */
    private const val MAX_ACCURACY_M = 50f
    private const val MAX_SPEED_MPS = 60f
    private const val MIN_DISTANCE_M = 3f

    /** Rejects points that are too imprecise or imply an impossible speed jump. */
    fun shouldAccept(previous: TrackPoint?, candidate: TrackPoint): Boolean {
        if (candidate.accuracy > MAX_ACCURACY_M) return false
        if (previous == null) return true
        val dtSec = (candidate.timestamp - previous.timestamp) / 1000f
        if (dtSec > 0f) {
            val speed = GeoUtils.distanceMeters(previous, candidate) / dtSec
            if (speed > MAX_SPEED_MPS) return false
        }
        return true
    }

    /** True when the movement is large enough to be added to the travelled distance. */
    fun isSignificantMove(previous: TrackPoint?, candidate: TrackPoint): Boolean {
        if (previous == null) return false
        return GeoUtils.distanceMeters(previous, candidate) >= MIN_DISTANCE_M
    }
}