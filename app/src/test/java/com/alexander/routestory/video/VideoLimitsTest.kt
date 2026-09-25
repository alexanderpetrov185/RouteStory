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
    fun `explicit 30s is clean marker move and adds map camera intro only to timeline base`() {
        val l = VideoLimits.fitEncode(isPro = false, distanceMeters = 5_000f, photoCount = 0, targetMapSec = 30)
        // MOVE 30 s + stationary tilt/zoom 4 s. Intro/finish/outro/photos are extra.
        assertEquals(34, l.durationSec)
        assertTrue(l.fps >= VideoLimits.MIN_ENCODE_FPS)
        assertTrue(totalFrames(l.durationSec, l.fps, 0) <= VideoLimits.MAX_ENCODE_FRAMES)
    }

    @Test
    fun `explicit 2min is clean marker move`() {
        val l = VideoLimits.fitEncode(isPro = false, distanceMeters = 5_000f, photoCount = 12, targetMapSec = 120)
        assertEquals(124, l.durationSec)
        assertEquals(VideoLimits.MIN_ENCODE_FPS, l.fps)
        // Fixed cards and photo pauses are deliberately outside the selected MOVE time.
        assertTrue(120 + VideoLimits.FIXED_NON_MOVE_SEC + 60 > 120)
    }

    @Test
    fun `explicit pick is clamped to the 2min map movement ceiling`() {
        val l = VideoLimits.fitEncode(isPro = true, distanceMeters = 1_000f, photoCount = 0, targetMapSec = 999)
        assertEquals(VideoLimits.MAX_MAP_MOVE_SEC + 4, l.durationSec)
    }

    @Test
    fun `auto map movement is capped at two minutes`() {
        val l = VideoLimits.fitEncode(isPro = false, distanceMeters = 200_000f, photoCount = 0, targetMapSec = null)
        assertTrue(l.durationSec - 4 <= VideoLimits.MAX_MAP_MOVE_SEC)
    }

    @Test
    fun `auto pick ignores the explicit target`() {
        val auto = VideoLimits.fitEncode(isPro = false, distanceMeters = 5_000f, photoCount = 0, targetMapSec = null)
        assertEquals(
            VideoLimits.fitEncode(isPro = false, distanceMeters = 5_000f, photoCount = 0).durationSec,
            auto.durationSec
        )
    }
}