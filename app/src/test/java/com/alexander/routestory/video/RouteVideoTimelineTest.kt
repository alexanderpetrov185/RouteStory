package com.alexander.routestory.video

import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteVideoTimelineTest {

    private fun point(lat: Double, lon: Double, ts: Long, speed: Float = 5f) = TrackPoint(
        lat = lat, lon = lon, altitude = 0.0, speed = speed, accuracy = 5f, timestamp = ts
    )

    /** A straight west-to-east route with 5 evenly spaced fixes, one second apart. */
    private fun track(photos: List<TrackPhoto> = emptyList()) = Track(
        id = 1,
        name = "test",
        startTime = 0L,
        endTime = 4_000L,
        distance = 400f,
        duration = 4_000L,
        points = (0..4).map { point(55.0, 37.0 + it * 0.001, it * 1_000L) },
        photos = photos
    )

    private fun photo(lat: Double, lon: Double, ts: Long) =
        TrackPhoto(trackId = 1, uri = "/tmp/p.jpg", timestamp = ts, latitude = lat, longitude = lon)

    @Test
    fun `no photos keeps the plain map duration`() {
        val timeline = RouteVideoTimeline(track(), fps = 15, durationSec = 12, maxPhotos = 10)
        assertEquals(0, timeline.photoCount)
        // 4 s intro + 12 s map + 5 s finish + 2 s outro, 15 fps.
        assertEquals(60 + timeline.baseFrames + timeline.finishFrames + 30, timeline.totalFrames)
        assertNull(timeline.photoAt(0))
        assertNull(timeline.photoAt(timeline.totalFrames - 1))
    }

    @Test
    fun `intro and outro cards are four and two seconds`() {
        val timeline = RouteVideoTimeline(track(), fps = 15, durationSec = 12, maxPhotos = 10)
        assertEquals(60, timeline.introFrames)
        assertEquals(30, timeline.outroFrames)
        assertTrue(timeline.isIntro(0) && timeline.isIntro(59) && !timeline.isIntro(60))
        assertTrue(timeline.isOutro(timeline.totalFrames - 1) && !timeline.isOutro(timeline.totalFrames - 31))
    }

    @Test
    fun `card fades stay under a quarter second`() {
        val timeline = RouteVideoTimeline(track(), fps = 15, durationSec = 12, maxPhotos = 10)
        // 15 fps * 0.25 s = ~4 frames of ramp.
        assertEquals(1f, timeline.introAlpha(3), 0.01f)
        assertTrue(timeline.introAlpha(0) < 1f)
        assertEquals(1f, timeline.outroAlpha(timeline.totalFrames - 1), 0.01f)
    }

    @Test
    fun `every photo adds a full-screen pause`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L), photo(55.0, 37.004, 4_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        assertEquals(2, timeline.photoCount)
        // Sprint 35: each pause is 5 s (PHOTO_HOLD_SEC) = 75 frames at 15 fps.
        // 60 intro + the tempo-safe map base + 2 * 75 pauses + finish + 30 outro.
        assertEquals(60 + timeline.baseFrames + 2 * 75 + timeline.finishFrames + 30, timeline.totalFrames)
    }

    @Test
    fun `photo pause happens where the photo sits on the route`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        // Half the route in MOVE: the exact frame is derived from the tempo-safe base.
        val start = (0 until timeline.totalFrames).first { timeline.photoAt(it) != null }
        assertTrue(kotlin.math.abs(start - (timeline.introFrames + timeline.tiltFrames + timeline.zoomFrames +
            (timeline.moveFrames() - 1) / 2)) <= 1)
        assertNotNull(timeline.photoAt(start))
        // Sprint 35: the pause is 5 s = 75 frames, so photoAt(start + 30) is STILL visible;
        // it only clears once the pause (including its fade tail) ends.
        assertNotNull(timeline.photoAt(start + 30))
        assertNull(timeline.photoAt(start + 75))
    }

    @Test
    fun `map progress freezes while a photo is visible`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        val start = (0 until timeline.totalFrames).first { timeline.photoAt(it) != null }
        val frozen = timeline.mapProgress(start)
        // Sprint 35: the pause is 5 s = 75 frames at 15 fps.
        for (f in start until start + 75) {
            assertEquals(frozen, timeline.mapProgress(f), 0.0001f)
        }
        // The marker resumes after the insert instead of jumping to the end.
        assertTrue(timeline.mapProgress(start + 75) > frozen)
    }

    @Test
    fun `progress still reaches the end of the route`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        assertEquals(1f, timeline.mapProgress(timeline.totalFrames - 1), 0.0001f)
    }

    @Test
    fun `fade stays within a quarter second`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        val start = (0 until timeline.totalFrames).first { timeline.photoAt(it) != null }
        val hold = 75 // Sprint 35: 5 s pause at 15 fps
        val ramped = (0 until hold).count { timeline.photoAlpha(start + it) < 1f }
        // 15 fps * 1 s fade (PHOTO_FADE_SEC) = 15 partially transparent frames per edge.
        assertTrue("ramped=$ramped", ramped in 25..32)
        assertEquals(1f, timeline.photoAlpha(start + hold / 2), 0.0001f)
    }

    @Test
    fun `photos beyond the limit are dropped`() {
        val photos = (1..5).map { photo(55.0, 37.0 + it * 0.0005, it * 500L) }
        val timeline = RouteVideoTimeline(track(photos), fps = 15, durationSec = 12, maxPhotos = 2)
        assertEquals(2, timeline.photoCount)
    }

    @Test
    fun `photo-heavy tracks shrink the per-photo hold by the encode budget`() {
        // Sprint 35: 20 photos * 5 s = 100 s of pause time, over the 60 s budget
        // (max(PHOTO_PAUSE_BUDGET_SEC, durationSec * 0.5f) = max(60, 6) = 60 s).
        // The per-photo hold shrinks to 60 / 20 = 3.0 s (above the 1.0 s floor).
        val photos = (1..20).map { photo(55.0, 37.0 + it * 0.0005, it * 500L) }
        val timeline = RouteVideoTimeline(track(photos), fps = 15, durationSec = 12, maxPhotos = 50)
        assertEquals(20, timeline.photoCount)
        assertEquals(3.0f, timeline.effectivePhotoHoldSec, 0.001f)
        // 3 s at 15 fps = 45 frames per pause.
        assertEquals(45, timeline.holdFrames)
        // The floor (MIN_PHOTO_HOLD_SEC) caps the shrink: 70 photos * 5 s = 350 s ->
        // 60 / 70 = 0.86 s, clamped to 1.0 s.
        val many = (1..70).map { photo(55.0, 37.0 + it * 0.00005f.toDouble(), it * 500L) }
        val busy = RouteVideoTimeline(track(many), fps = 15, durationSec = 12, maxPhotos = 100)
        assertEquals(RouteVideoTimeline.MIN_PHOTO_HOLD_SEC, busy.effectivePhotoHoldSec, 0.001f)
    }

    @Test
    fun `photo without coordinates falls back to its timestamp`() {
        val bare = TrackPhoto(trackId = 1, uri = "/tmp/p.jpg", timestamp = 3_000L, latitude = null, longitude = null)
        val timeline = RouteVideoTimeline(track(listOf(bare)), fps = 15, durationSec = 12, maxPhotos = 10)
        val start = (0 until timeline.totalFrames).first { timeline.photoAt(it) != null }
        // Sprint 41: the marker freezes on the PIN FRACTION itself (0.75), not on
        // its warp image - the pin is drawn from insertFraction (same source).
        assertEquals(timeline.insertFraction(0), timeline.mapProgress(start), 0.0001f)
        assertEquals(0.75f, timeline.mapProgress(start), 0.001f)
    }

    @Test
    fun `marker stands during tilt and zoom then moves`() {
        val timeline = RouteVideoTimeline(track(), fps = 15, durationSec = 12, maxPhotos = 10)
        val mapStart = timeline.introFrames
        // TILT ~3 s + ZOOM ~3 s: progress stays 0; followBlend ramps 0 -> 1 across ZOOM.
        for (f in mapStart until mapStart + timeline.tiltFrames + timeline.zoomFrames) {
            assertEquals(0f, timeline.mapProgress(f), 0.0001f)
            assertTrue(timeline.followBlend(f) <= 1f)
        }
        assertEquals(0f, timeline.tiltAmount(mapStart), 0.0001f)
        assertEquals(1f, timeline.tiltAmount(mapStart + timeline.tiltFrames), 0.0001f)
        assertEquals(0f, timeline.zoomAmount(mapStart + timeline.tiltFrames), 0.0001f)
        assertEquals(1f, timeline.zoomAmount(mapStart + timeline.tiltFrames + timeline.zoomFrames), 0.0001f)
        val moveStart = mapStart + timeline.tiltFrames + timeline.zoomFrames
        assertTrue(timeline.mapProgress(moveStart + 10) > 0f)
        // Sprint 34: followBlend is smooth: 0 before ZOOM, ramps through ZOOM, 1 in MOVE.
        assertEquals(0f, timeline.followBlend(mapStart + timeline.tiltFrames), 0.0001f)
        val zoomMid = mapStart + timeline.tiltFrames + timeline.zoomFrames / 2
        val midBlend = timeline.followBlend(zoomMid)
        assertTrue("midBlend=$midBlend", midBlend > 0.2f && midBlend < 0.9f)
        assertEquals(1f, timeline.followBlend(moveStart), 0.0001f)
        assertEquals(1f, timeline.followBlend(moveStart + 50), 0.0001f)
    }

    @Test
    fun `max speed peak warps monotonically`() {
        val pts = (0..9).map { i ->
            point(55.0, 37.0 + i * 0.001, i * 1_000L, if (i in 4..6) 26.4f else 5f)
        }
        val t = track().copy(points = pts)
        val timeline = RouteVideoTimeline(t, fps = 15, durationSec = 30, maxPhotos = 10)
        assertNotNull(timeline.peakFraction)
        assertEquals(26.4f, timeline.peakSpeedMps, 0.01f)
        var prev = -1f
        for (i in 0..100) {
            val v = timeline.warpMove(i / 100f)
            assertTrue(v >= prev - 1e-6f)
            prev = v
        }
        assertEquals(0f, timeline.warpMove(0f), 0.0001f)
        assertEquals(1f, timeline.warpMove(1f), 0.0001f)
        // Slower slope around the peak: warp step smaller than raw step there.
        val c = timeline.peakFraction!!
        // Warp is continuous: steps of 0.01 stay close to raw steps near the peak.
        assertTrue((timeline.warpMove(c + 0.01f) - timeline.warpMove(c - 0.01f)) < 0.02f)
        // Slow-mo: the step around the peak is slower than the average raw step.
        val stepPeak = (timeline.warpMove(0.55f) - timeline.warpMove(0.45f)).coerceAtLeast(0f)
        assertTrue(stepPeak < 0.1f)
    }

    @Test
    fun `peak boost window is tight and returns to normal`() {
        val pts = (0..20).map { i ->
            val speed = if (i in 9..11) 26.4f else 6f
            point(55.0, 37.0 + i * 0.001, i * 1_000L, speed)
        }
        val t = track().copy(points = pts)
        val timeline = RouteVideoTimeline(t, fps = 15, durationSec = 30, maxPhotos = 10)
        assertNotNull(timeline.peakFraction)
        val pf = timeline.peakFrame
        assertTrue(pf > 0)
        assertEquals(1f, timeline.peakBoost(pf), 0.01f)
        val half = (RouteVideoTimeline.PEAK_BOOST_HALF_SEC * 15).toInt() + 2
        assertEquals(0f, timeline.peakBoost(pf - half), 0.001f)
        assertEquals(0f, timeline.peakBoost(pf + half), 0.001f)
    }

    @Test
    fun `photo opens exactly when the marker reaches its pin`() {
        // 20 fixes, 1 s apart, ~111 m apart (walking ~5 m/s): the global tempo
        // stretch makes warpMove != identity, so a photo bound to the nearest FIX
        // (the old code) opened the pause where the marker was not yet on the pin.
        val points = (0..19).map { i -> point(55.0, 37.0 + i * 0.001, i * 1_000L, 5f) }
        val base = track().copy(points = points, distance = 2_100f, duration = 19_000L)
        // Photo taken 4.5 s in: exactly between fix 4 and fix 5 (fraction 0.225).
        val timeline = RouteVideoTimeline(
            base.copy(photos = listOf(photo(55.0, 37.0045, 4_500L))),
            fps = 15, durationSec = 30, maxPhotos = 10
        )
        val start = timeline.photoStartFrame(0)
        assertTrue("no photo insert scheduled", start > 0)
        assertNotNull(timeline.photoAt(start))
        // Sprint 41: pause starts on the frame the marker reaches the pin, and the
        // marker freezes exactly on the pin fraction the renderer draws from.
        val pin = timeline.insertFraction(0)
        val marker = timeline.mapProgress(start)
        assertEquals(pin, marker, 0.0001f)
        assertTrue(
            "marker must approach the pin before the pause: prev=${timeline.mapProgress(start - 1)} pin=$pin",
            timeline.mapProgress(start - 1) < pin
        )
        // The pause is frozen on the pin position for the whole hold.
        for (f in start until start + timeline.holdFrames) {
            assertEquals(marker, timeline.mapProgress(f), 0.0001f)
            assertNotNull(timeline.photoAt(f))
        }
    }

    @Test
    fun `no peak without clear speedup`() {
        val timeline = RouteVideoTimeline(track(), fps = 15, durationSec = 12, maxPhotos = 10)
        assertNull(timeline.peakFraction)
        // Sprint 47: no global time-warp anymore - without a peak the move is the
        // identity (even tempo), monotonic with warp(0)=0, warp(1)=1.
        assertEquals(0f, timeline.warpMove(0f), 0.0001f)
        assertEquals(1f, timeline.warpMove(1f), 0.0001f)
        assertEquals(0.5f, timeline.warpMove(0.5f), 0.0001f)
        assertEquals(0.75f, timeline.warpMove(0.75f), 0.0001f)
    }

    @Test
    fun `driving track keeps the normal tempo`() {
        // 10 points at 20 m/s (72 km/h) each, 1 s apart.
        val pts = (0..9).map { i ->
            point(55.0, 37.0 + i * 0.001, i * 1_000L, 20f)
        }
        val t = track().copy(points = pts)
        val timeline = RouteVideoTimeline(t, fps = 15, durationSec = 30, maxPhotos = 10)
        // Sprint 47: even tempo - raw progress maps 1:1 (no clear peak in a flat track).
        assertEquals(0.5f, timeline.warpMove(0.5f), 0.0001f)
        assertEquals(0.25f, timeline.warpMove(0.25f), 0.0001f)
    }

    @Test
    fun `walking track keeps an even tempo`() {
        // 10 points at 2 m/s (7.2 km/h) each, 1 s apart.
        val pts = (0..9).map { i ->
            point(55.0, 37.0 + i * 0.001, i * 1_000L, 2f)
        }
        val t = track().copy(points = pts)
        val timeline = RouteVideoTimeline(t, fps = 15, durationSec = 30, maxPhotos = 10)
        // Sprint 47: no fast-slow-fast warp on short rides - identity end to end,
        // monotonic 0 -> 1.
        var prev = -1f
        for (i in 0..100) {
            val v = timeline.warpMove(i / 100f)
            assertTrue("v=$v prev=$prev at i=$i", v >= prev - 1e-6f)
            assertEquals("at i=$i", i / 100f, v, 0.001f)
            prev = v
        }
    }

    @Test
    fun `finish section holds the marker before the outro`() {
        val timeline = RouteVideoTimeline(track(), fps = 15, durationSec = 12, maxPhotos = 10)
        // Sprint 49: 5 s finish at 15 fps between the map and the outro.
        assertEquals(5 * 15, timeline.finishFrames)
        assertEquals(timeline.finishStartFrame, timeline.outroStartFrame - timeline.finishFrames)
        assertEquals((4 + 12 + 5 + 2) * 15, timeline.totalFrames)
        // The marker sits at the route end for the whole finish.
        assertEquals(1f, timeline.mapProgress(timeline.finishStartFrame), 0.0001f)
        assertEquals(1f, timeline.mapProgress(timeline.outroStartFrame - 1), 0.0001f)
        assertTrue(timeline.isFinish(timeline.finishStartFrame))
        assertTrue(!timeline.isFinish(timeline.outroStartFrame))
        assertNull(timeline.photoAt(timeline.finishStartFrame))
    }

    @Test
    fun `finish pulls back to the overview and straightens the tilt`() {
        val timeline = RouteVideoTimeline(track(), fps = 15, durationSec = 12, maxPhotos = 10)
        val fin = timeline.finishStartFrame
        val out = timeline.outroStartFrame
        // finishAmount: 0 at the finish start, smooth through the middle, 1 at the outro.
        assertEquals(0f, timeline.finishAmount(fin), 0.0001f)
        val mid = timeline.finishAmount(fin + timeline.finishFrames / 2)
        assertTrue("mid=$mid", mid > 0.2f && mid < 0.8f)
        assertEquals(1f, timeline.finishAmount(out), 0.0001f)
        assertEquals(1f, timeline.finishAmount(out + 5), 0.0001f)
        // Monotonic pull-back.
        var prev = -1f
        for (f in fin until out) {
            val v = timeline.finishAmount(f)
            assertTrue("v=$v prev=$prev at f=$f", v >= prev - 1e-6f)
            prev = v
        }
        // Tilt straightens 30° -> 0° (amount 1 -> 0) and the outro stays flat.
        assertEquals(1f, timeline.tiltAmount(fin), 0.0001f)
        assertTrue(timeline.tiltAmount(fin + timeline.finishFrames / 2) < 1f)
        assertEquals(0f, timeline.tiltAmount(out), 0.0001f)
        assertEquals(0f, timeline.tiltAmount(out + 5), 0.0001f)
        // The map is still flat at the intro and fully tilted through MOVE.
        assertEquals(0f, timeline.tiltAmount(0), 0.0001f)
        assertEquals(1f, timeline.tiltAmount(timeline.introFrames + timeline.tiltFrames), 0.0001f)
    }
}