package com.alexander.routestory.video

import com.alexander.routestory.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteVideoMotionTest {
    private fun point(lon: Double) = TrackPoint(0.0, lon, 0.0, 0f, 1f, 0L)

    @Test fun timelineIncludesBothEndpoints() {
        assertEquals(0f, RouteVideoMotion.progress(0, 360), 0f)
        assertEquals(1f, RouteVideoMotion.progress(359, 360), 0f)
    }

    @Test fun progressUsesDistanceNotPointIndex() {
        val motion = RouteVideoMotion(listOf(point(0.0), point(0.001), point(0.010)))
        assertEquals(1f, motion.position(0.1f), 0.0001f)
        assertEquals(1f + 4f / 9f, motion.position(0.5f), 0.0001f)
        assertEquals(2f, motion.position(1f), 0.0001f)
    }

    @Test fun repeatedAndSinglePointsAreSafe() {
        assertEquals(0f, RouteVideoMotion(listOf(point(0.0))).position(1f), 0f)
        assertEquals(1f, RouteVideoMotion(listOf(point(0.0), point(0.0))).position(1f), 0f)
        val motion = RouteVideoMotion(listOf(point(0.0), point(0.0), point(0.01)))
        assertEquals(1.5f, motion.position(0.5f), 0.0001f)
    }

    @Test fun overviewThenSmoothFollow() {
        assertEquals(0f, RouteVideoMotion.followBlend(0f), 0f)
        assertEquals(0f, RouteVideoMotion.followBlend(0.1f), 0f)
        assertEquals(0.5f, RouteVideoMotion.followBlend(0.2f), 0.0001f)
        assertEquals(1f, RouteVideoMotion.followBlend(1f), 0f)
    }
}
