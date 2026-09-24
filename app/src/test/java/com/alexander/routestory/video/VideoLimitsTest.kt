package com.alexander.routestory.video

import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLimitsTest {

    /** Mirrors VideoLimits.fitEncode.totalFrames (worst-case photo pauses). */
    private fun totalFrames(sec: Int, fps: Int, photoCount: Int): Int {
        val introOutro = (6 * fps)
        val photoSec = minOf(photoCount * 5f, maxOf(60f, sec * 0.5f)).toInt()
        return introOutro + sec * fps + photoSec * fps
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
}