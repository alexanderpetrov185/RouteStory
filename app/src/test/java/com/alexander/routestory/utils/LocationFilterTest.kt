package com.alexander.routestory.utils

import com.alexander.routestory.domain.TrackPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sprint 68: standstill / indoor jitter rules of the GPS filter. */
class LocationFilterTest {

    /** Latitude offset that is worth roughly [meters] on the ground. */
    private fun latOffset(meters: Double): Double = meters / 111_320.0

    private fun point(offsetMeters: Double, accuracy: Float, ts: Long = 0L) = TrackPoint(
        lat = latOffset(offsetMeters), lon = 0.0, altitude = 0.0,
        speed = 0f, accuracy = accuracy, timestamp = ts
    )

    @Test fun coarseFixIsAlwaysRejected() {
        val previous = point(0.0, 10f)
        assertFalse(LocationFilter.shouldAccept(previous, point(0.0, 60f)))
    }

    @Test fun impossibleSpeedJumpIsRejected() {
        val previous = point(0.0, 5f, ts = 0L)
        // ~1000 m in one second: far beyond MAX_SPEED_MPS.
        assertFalse(LocationFilter.shouldAccept(previous, point(1_000.0, 5f, ts = 1_000L)))
    }

    @Test fun indoorJitterInsideRecordDistanceIsRejected() {
        val previous = point(0.0, 10f)
        // 2 m away with a 30 m accuracy: a table-standing phone, not a movement.
        assertFalse(LocationFilter.shouldAcceptWhileStationary(previous, point(2.0, 30f)))
    }

    @Test fun preciseFixWhileStandingIsAccepted() {
        val previous = point(0.0, 5f)
        assertTrue(LocationFilter.shouldAcceptWhileStationary(previous, point(2.0, 8f)))
    }

    @Test fun realMoveIsAcceptedEvenWithMediocreAccuracy() {
        val previous = point(0.0, 10f)
        // 20 m of real movement: street speed, must never be filtered out.
        assertTrue(LocationFilter.shouldAcceptWhileStationary(previous, point(20.0, 35f)))
    }

    @Test fun tinyDriftIsNotWorthARecordedPoint() {
        val previous = point(0.0, 5f)
        assertFalse(LocationFilter.isRecordableMove(previous, point(2.0, 5f)))
    }

    @Test fun walkingStepIsRecorded() {
        val previous = point(0.0, 5f)
        assertTrue(LocationFilter.isRecordableMove(previous, point(8.0, 5f)))
    }

    @Test fun firstFixIsAlwaysRecordable() {
        assertTrue(LocationFilter.isRecordableMove(null, point(0.0, 50f)))
    }
}