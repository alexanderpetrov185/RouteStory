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

    /**
     * Sprint 68: shortest movement that is written to the recorded track.
     *
     * A phone lying on a table inside reports every fix a few metres away from the previous
     * one; storing those points drew a "spaghetti" line on a ride that never moved. 5 m is
     * below a single walking step per update (2 s), so a real street track is unchanged.
     */
    private const val MIN_RECORD_DISTANCE_M = 5f

    /**
     * Sprint 68: while standing still, only a precise fix may be trusted at all.
     *
     * Indoors the receiver reports 30-60 m accuracy and every fix lands 10-40 m away - right
     * inside [MAX_ACCURACY_M], so the old filter let them through. A fix closer than
     * [MIN_RECORD_DISTANCE_M] to the last recorded point is therefore accepted only when the
     * receiver is actually precise.
     */
    private const val STATIONARY_ACCURACY_M = 20f

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

    /**
     * Sprint 68: standstill guard. True when the fix is either a real move (at least
     * [MIN_RECORD_DISTANCE_M] away) or precise enough to be believed as a standing micro-move.
     */
    fun shouldAcceptWhileStationary(previous: TrackPoint?, candidate: TrackPoint): Boolean {
        if (previous == null) return true
        if (GeoUtils.distanceMeters(previous, candidate) >= MIN_RECORD_DISTANCE_M) return true
        return candidate.accuracy <= STATIONARY_ACCURACY_M
    }

    /** Sprint 68: true when the movement is big enough to be stored as a track point. */
    fun isRecordableMove(previous: TrackPoint?, candidate: TrackPoint): Boolean {
        if (previous == null) return true
        return GeoUtils.distanceMeters(previous, candidate) >= MIN_RECORD_DISTANCE_M
    }

    /** True when the movement is large enough to be added to the travelled distance. */
    fun isSignificantMove(previous: TrackPoint?, candidate: TrackPoint): Boolean {
        if (previous == null) return false
        return GeoUtils.distanceMeters(previous, candidate) >= MIN_DISTANCE_M
    }
}