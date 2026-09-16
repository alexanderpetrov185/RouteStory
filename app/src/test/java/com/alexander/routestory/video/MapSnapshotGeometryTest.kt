package com.alexander.routestory.video

import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MapSnapshotGeometryTest {
    @Test fun shortTrackHasStreetZoomAspectAndVisibleEndpoints() {
        val points = listOf(
            TrackPoint(55.75, 37.60, 0.0, 0f, 1f, 0),
            TrackPoint(55.756, 37.608, 0.0, 0f, 1f, 1000)
        )
        val track = Track(name = "test", startTime = 0, endTime = 1000,
            distance = 800f, duration = 1000, points = points)
        val crop = MapSnapshotProvider.cropInfo(track, 640, 360)!!
        assertTrue(crop.zoom in 14..16)
        assertTrue(crop.fullW / 256 * (crop.fullH / 256) <= 9)
        assertEquals(640.0 / 360, crop.cropW.toDouble() / crop.cropH, 0.02)
        for (point in points) {
            val world = 256.0 * (1 shl crop.zoom)
            val x = ((point.lon + 180) / 360 * world - crop.x0 * 256 - crop.leftPx) * 640 / crop.cropW
            val lat = Math.toRadians(point.lat)
            val y = ((1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * world - crop.y0 * 256 - crop.topPx) * 360 / crop.cropH
            assertTrue("x=$x", x in 30.0..610.0)
            assertTrue("y=$y", y in 30.0..330.0)
        }
    }
}
