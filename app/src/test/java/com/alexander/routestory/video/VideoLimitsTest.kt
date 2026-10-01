package com.alexander.routestory.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLimitsTest {

    /** Mirrors total frames: MOVE + tilt/zoom + photo pauses + fixed cards. */
    private fun totalFrames(limitsDurationSec: Int, fps: Int, photoCount: Int): Int {
        val count = minOf(photoCount, 10)
        val photoSec = if (count == 0) 0 else {
            val hold = (maxOf(60f, limitsDurationSec * 0.5f) / count).coerceIn(1f, 5f)
            (hold * count).toInt()
        }
        return (limitsDurationSec + 11 + photoSec) * fps
    }

    @Test
    fun `long distance gets a much longer map part but stays within the frame cap`() {
        val free = VideoLimits.fitEncode(isPro = false, distanceMeters = 40_000f, photoCount = 0)
        // Old tempo capped this at 45 s; Sprint 47 aims at ~2x slower marker.
        assertTrue("durationSec=${free.durationSec}", free.durationSec >= 60)
        assertTrue(
            totalFrames(free.durationSec, free.fps, 0) <= VideoLimits.MAX_ENCODE_FRAMES
        )
    }

    @Test
    fun `short walk keeps the floor duration`() {
        val short = VideoLimits.fitEncode(isPro = false, distanceMeters = 1_000f, photoCount = 0)
        assertTrue("durationSec=${short.durationSec}", short.durationSec <= 16)
        assertTrue(short.durationSec >= VideoLimits.MIN_ENCODE_SEC)
    }

    @Test
    fun `photo heavy pro export still fits the frame cap`() {
        val pro = VideoLimits.fitEncode(isPro = true, distanceMeters = 30_000f, photoCount = 12)
        assertTrue(pro.fps >= VideoLimits.MIN_ENCODE_FPS)
        assertTrue(pro.durationSec >= VideoLimits.MIN_ENCODE_SEC)
        assertTrue(
            totalFrames(pro.durationSec, pro.fps, 12) <= VideoLimits.MAX_ENCODE_FRAMES
        )
    }

    @Test
    fun `map movement never exceeds the two minute ceiling`() {
        val l = VideoLimits.fitEncode(isPro = false, distanceMeters = 200_000f, photoCount = 0)
        // MOVE = durationSec minus the 4 stationary camera seconds.
        assertTrue(l.durationSec - 4 <= VideoLimits.MAX_MAP_MOVE_SEC)
    }

    @Test
    fun `both tiers stay under the ceiling on a very long ride`() {
        for (isPro in listOf(false, true)) {
            val l = VideoLimits.fitEncode(isPro, distanceMeters = 500_000f, photoCount = 12)
            assertTrue(
                "isPro=$isPro durationSec=${l.durationSec}",
                l.durationSec - 4 <= VideoLimits.MAX_MAP_MOVE_SEC
            )
            assertTrue(
                totalFrames(l.durationSec, l.fps, 12) <= VideoLimits.MAX_ENCODE_FRAMES
            )
        }
    }

    @Test
    fun `duration only depends on the ride itself`() {
        // Sprint 63: no user duration exists any more, so the same ride must always
        // produce the same budget (idempotent, no hidden state).
        val a = VideoLimits.fitEncode(isPro = false, distanceMeters = 5_000f, photoCount = 3)
        val b = VideoLimits.fitEncode(isPro = false, distanceMeters = 5_000f, photoCount = 3)
        assertEquals(a.durationSec, b.durationSec)
        assertEquals(a.fps, b.fps)
    }
}