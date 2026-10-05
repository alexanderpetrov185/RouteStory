package com.alexander.routestory.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sprint 70: the video camera never pans the frame-sized backdrop past its edge. */
class CoverClampTest {

    private fun clamp(
        cx: Float, cy: Float, z: Float, w: Float = 720f, h: Float = 1280f,
        box: FloatArray = floatArrayOf(0f, w, 0f, h)
    ) = MapSnapshotProvider.clampCenterForCover(
        cx, cy, box[0], box[1], box[2], box[3], z, w, h
    )

    @Test fun edgeMarkerAtZoomOneIsClampedToCentre() {
        // Marker hugging the left edge during TILT (z ~= 1): the required window
        // [C-w/2, C+w/2] would leave the left third of the frame on bgPaint.
        val (x, _) = clamp(0.17f * 720f, 640f, 1f)
        assertEquals(360f, x, 0.001f)
    }

    @Test fun edgeMarkerAtZoomFourPassesThrough() {
        // Same marker at z=4: the window C +/- w/8 fits inside the content rect,
        // so the zoom-in still reaches the marker exactly (no forced centre).
        val (x, _) = clamp(0.17f * 720f, 640f, 4f)
        assertEquals(0.17f * 720f, x, 0.001f)
    }

    @Test fun frameCentreIsNeverClamped() {
        for (z in listOf(1f, 2f, 4f, 6f, 32f)) {
            val (x, y) = clamp(360f, 640f, z)
            assertEquals("z=$z", 360f, x, 0.001f)
            assertEquals("z=$z", 640f, y, 0.001f)
        }
    }

    @Test fun moveZoomIsEffectivelySlack() {
        // z=6: bounds are within ~8% of the edges, so a real marker position
        // (well inside the padded crop) keeps the exact centre on the marker.
        val (x, y) = clamp(360f, 640f, 6f)
        assertEquals(360f, x, 0.001f)
        assertEquals(640f, y, 0.001f)
        val (x2, _) = clamp(0.30f * 720f, 640f, 6f)
        assertEquals(0.30f * 720f, x2, 0.001f)
    }

    @Test fun tiltedBoxTightensTheBounds() {
        // Tilt warps the frame corners: the inverse bbox is wider than the frame,
        // so at z=1 the valid centre range collapses deeper toward the middle.
        val w = 720f
        val h = 1280f
        val (x, _) = clamp(
            0.30f * w, 640f, 1f, w, h,
            floatArrayOf(-0.1f * w, 1.1f * w, -0.1f * h, 1.1f * h)
        )
        assertTrue("x=$x should be past the raw 0.30w", x > 0.30f * w)
    }

    @Test fun degenerateZoomFallsBackToCentre() {
        val (x, y) = clamp(100f, 200f, 0f)
        assertEquals(360f, x, 0.001f)
        assertEquals(640f, y, 0.001f)
    }
}
