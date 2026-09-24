package com.alexander.routestory.video

import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Sprint 40 regression guard: the follow segment and the overview must share ONE
 * coordinate space. The old code projected the overlay from a marker-centred crop
 * that the renderer recomputed per frame, while the bitmap on screen was the
 * segment crop - the map "drifted" and roads slid between segments.
 *
 * Here we assert that a geo point maps to the same output pixel whether it is
 * computed inside the overview crop or inside the segment rectangle.
 */
class FollowRectGeometryTest {

    private fun track(): Track {
        val points = (0..20).map { i ->
            TrackPoint(55.75 + i * 0.0002, 37.60 + i * 0.0006, 0.0, 5f, 5f, i * 1_000L)
        }
        return Track(
            name = "geo", startTime = 0, endTime = 20_000,
            distance = 1_500f, duration = 20_000, points = points
        )
    }

    private fun mercX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * (1 shl zoom) * 256.0

    private fun mercY(lat: Double, zoom: Int): Double {
        val rad = Math.toRadians(lat)
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl zoom) * 256.0
    }

    @Test
    fun `follow rect and overview agree on every pixel`() {
        val track = track()
        val w = 640
        val h = 360
        val ov = MapSnapshotProvider.cropInfo(track, w, h)!!
        // A marker position used as the segment centre.
        val lat = 55.7517
        val lon = 37.6051
        val seg = MapSnapshotProvider.followCropInfo(lat, lon, ov, w, h)

        val rect = MapSnapshotProvider.followRectInOverview(seg, ov, w, h)
        assertNotNull("segment must map into the overview frame", rect)
        val r = rect!!
        assertTrue("rect inside the frame: ${r.toList()}", r[0] > -w && r[2] < 2 * w)

        // Overview frame pixel of a point...
        val xOv = (mercX(lon, ov.zoom) - (ov.x0 * 256 + ov.leftPx)) * w / ov.cropW
        val yOv = (mercY(lat, ov.zoom) - (ov.y0 * 256 + ov.topPx)) * h / ov.cropH
        // ...must equal its position inside the segment rectangle.
        val rectW = (r[2] - r[0]).toDouble()
        val rectH = (r[3] - r[1]).toDouble()
        val xSeg = r[0].toDouble() + (mercX(lon, seg.zoom) - (seg.x0 * 256 + seg.leftPx)) * rectW / seg.cropW
        val ySeg = r[1].toDouble() + (mercY(lat, seg.zoom) - (seg.y0 * 256 + seg.topPx)) * rectH / seg.cropH

        assertEquals("x drift", xOv, xSeg, 0.5)
        assertEquals("y drift", yOv, ySeg, 0.5)
        // Sprint 41: dz = seg.zoom - ov.zoom is arbitrary (>= 1); size scales by 2^-dz.
        val factor = (1 shl (seg.zoom - ov.zoom)).toDouble()
        assertEquals(w * seg.cropW / (factor * ov.cropW), rectW, 1.0)
        assertEquals(h * seg.cropH / (factor * ov.cropH), rectH, 1.0)
    }

    @Test
    fun `follow segment count keeps centre spacing within half window`() {
        val routeLength = 20_000.0
        val windowWidth = 2_000.0
        val count = adaptiveFollowSegmentCount(routeLength, windowWidth)
        assertEquals(21, count)
        assertTrue(count <= MAX_FOLLOW_SEGMENTS)
        assertTrue(routeLength / count <= windowWidth / 2.0)

        assertEquals(1, adaptiveFollowSegmentCount(0.0, windowWidth))
        assertTrue(adaptiveFollowSegmentCount(200_000.0, windowWidth) > MAX_FOLLOW_SEGMENTS)
    }

    @Test
    fun `follow rect is rejected when zooms do not line up`() {
        val track = track()
        val ov = MapSnapshotProvider.cropInfo(track, 640, 360)!!
        // dz = 0 -> no mapping, overview only.
        assertNull(MapSnapshotProvider.followRectInOverview(ov, ov, 640, 360))
    }
}
