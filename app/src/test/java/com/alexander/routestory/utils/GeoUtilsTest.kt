package com.alexander.routestory.utils

import com.alexander.routestory.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Sprint 68: elevation metrics (noise-filtered) and the start-end time window. */
class GeoUtilsTest {

    private fun point(altitude: Double) = TrackPoint(
        lat = 55.0, lon = 37.0, altitude = altitude, speed = 0f, accuracy = 5f, timestamp = 0L
    )

    @Test fun flatJitterIsNotClimb() {
        // +/- 1 m sawtooth around 100 m: pure GPS noise, must add up to zero.
        val points = listOf(100.0, 101.0, 99.0, 100.5, 99.5, 100.0).map(::point)
        assertEquals(0f, GeoUtils.totalAscent(points), 0.001f)
        assertEquals(0f, GeoUtils.totalDescent(points), 0.001f)
    }

    @Test fun realClimbIsSummed() {
        // Up 10, a 2 m dip (inside the noise band -> ignored), up 8 more from the last
        // confirmed reference: 10 + 8 = 18 m. The dip deliberately does NOT create a new
        // reference, otherwise every jittery step would inflate the total.
        val points = listOf(100.0, 110.0, 108.0, 118.0).map(::point)
        assertEquals(18f, GeoUtils.totalAscent(points), 0.001f)
    }

    @Test fun twoSeparateClimbsWithARealDipAreBothCounted() {
        // Up 10, down 5 (a real descent -> new reference), up 10: 20 m of climb.
        val points = listOf(100.0, 110.0, 105.0, 115.0).map(::point)
        assertEquals(20f, GeoUtils.totalAscent(points), 0.001f)
        assertEquals(5f, GeoUtils.totalDescent(points), 0.001f)
    }

    @Test fun descentMirrorsAscent() {
        val points = listOf(200.0, 180.0, 182.0, 160.0).map(::point)
        assertEquals(40f, GeoUtils.totalDescent(points), 0.001f)
        assertEquals(0f, GeoUtils.totalAscent(points), 0.001f)
    }

    @Test fun singlePointHasNoElevationChange() {
        assertEquals(0f, GeoUtils.totalAscent(listOf(point(100.0))), 0.001f)
    }

    @Test fun sameDayRangeKeepsOneDate() {
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 14); set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val text = GeoUtils.formatTimeRange(day, day + 48 * 60_000L)
        assertTrue(text, text.contains("08:14"))
        assertTrue(text, text.contains("\u2013"))
        // One date only: the day appears exactly once.
        assertEquals(1, Regex("\\d{2}\\.\\d{2}\\.\\d{4}").findAll(text).count())
    }

    @Test fun crossMidnightRangeKeepsBothDates() {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 50); set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val text = GeoUtils.formatTimeRange(start, start + 40 * 60_000L)
        assertEquals(2, Regex("\\d{2}\\.\\d{2}\\.\\d{4}").findAll(text).count())
    }

    @Test fun elevationIsFormattedInMetres() {
        assertTrue(GeoUtils.formatElevation(482.4f), GeoUtils.formatElevation(482.4f).startsWith("482"))
    }

    @Test fun cumulativeAscentEndsAtTheTotal() {
        val points = listOf(100.0, 110.0, 108.0, 118.0, 100.0).map(::point)
        val cumulative = GeoUtils.cumulativeAscent(points)
        assertEquals(points.size, cumulative.size)
        assertEquals(0f, cumulative.first(), 0.001f)
        assertEquals(GeoUtils.totalAscent(points), cumulative.last(), 0.001f)
        // Monotonic: the HUD must never count the climb back down.
        for (i in 1 until cumulative.size) {
            assertTrue("step $i", cumulative[i] >= cumulative[i - 1])
        }
    }

    @Test fun cumulativeDescentEndsAtTheTotal() {
        val points = listOf(200.0, 190.0, 192.0, 182.0, 200.0).map(::point)
        val cumulative = GeoUtils.cumulativeDescent(points)
        assertEquals(points.size, cumulative.size)
        assertEquals(0f, cumulative.first(), 0.001f)
        assertEquals(GeoUtils.totalDescent(points), cumulative.last(), 0.001f)
        // Monotonic too: the descent total must never shrink along the route.
        for (i in 1 until cumulative.size) {
            assertTrue("step $i", cumulative[i] >= cumulative[i - 1])
        }
    }

    @Test fun elevationProfileSuppressesJitter() {
        // Indoor +/- 1 m sawtooth: the chart line must be nearly flat, not spiky.
        val points = listOf(100.0, 101.0, 99.0, 100.5, 99.5, 100.0).map(::point)
        val profile = GeoUtils.elevationProfile(points)
        assertEquals(points.size, profile.size)
        val range = profile.maxOrNull()!! - profile.minOrNull()!!
        assertTrue("jitter range $range", range < 2f)
    }

    @Test fun elevationProfileKeepsHillShape() {
        // Up then down: the smoothed peak stays at the real summit and the
        // descent side really goes DOWN (not a monotone climb chart).
        val points = listOf(100.0, 105.0, 110.0, 115.0, 110.0, 105.0, 100.0).map(::point)
        val profile = GeoUtils.elevationProfile(points, 3)
        assertEquals(points.size, profile.size)
        val peak = profile.indices.maxByOrNull { profile[it] }!!
        assertTrue("peak index $peak", peak in 2..4)
        assertTrue(profile.last() < profile[peak] - 5f)
        assertTrue(profile.first() < profile[peak] - 5f)
    }

    @Test fun elevationProfileSizeMatchesPoints() {
        assertEquals(0, GeoUtils.elevationProfile(emptyList()).size)
        val two = listOf(point(100.0), point(105.0))
        assertEquals(2, GeoUtils.elevationProfile(two).size)
        assertEquals(105f, GeoUtils.elevationProfile(two).last(), 0.001f)
    }
}