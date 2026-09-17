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

    private fun point(lat: Double, lon: Double, ts: Long) = TrackPoint(
        lat = lat, lon = lon, altitude = 0.0, speed = 5f, accuracy = 5f, timestamp = ts
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
        assertEquals(180, timeline.totalFrames)
        assertNull(timeline.photoAt(0))
        assertNull(timeline.photoAt(179))
    }

    @Test
    fun `every photo adds a full-screen pause`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L), photo(55.0, 37.004, 4_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        assertEquals(2, timeline.photoCount)
        // 180 map frames + 2 pauses of 2.6s at 15 fps.
        assertEquals(180 + 2 * 39, timeline.totalFrames)
    }

    @Test
    fun `photo pause happens where the photo sits on the route`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        // Half the route => frame 90 is where the pause starts.
        val start = (0 until timeline.totalFrames).first { timeline.photoAt(it) != null }
        assertEquals(90, start)
        assertNotNull(timeline.photoAt(start))
        assertNull(timeline.photoAt(start + 39))
    }

    @Test
    fun `map progress freezes while a photo is visible`() {
        val track = track(listOf(photo(55.0, 37.002, 2_000L)))
        val timeline = RouteVideoTimeline(track, fps = 15, durationSec = 12, maxPhotos = 10)
        val start = (0 until timeline.totalFrames).first { timeline.photoAt(it) != null }
        val frozen = timeline.mapProgress(start)
        for (f in start until start + 39) {
            assertEquals(frozen, timeline.mapProgress(f), 0.0001f)
        }
        // The marker resumes after the insert instead of jumping to the end.
        assertTrue(timeline.mapProgress(start + 39) > frozen)
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
        val hold = 39
        val ramped = (0 until hold).count { timeline.photoAlpha(start + it) < 1f }
        // 15 fps * 0.25 s = at most 4 frames are partially transparent per edge.
        assertTrue("ramped=$ramped", ramped <= 8)
        assertEquals(1f, timeline.photoAlpha(start + hold / 2), 0.0001f)
    }

    @Test
    fun `photos beyond the limit are dropped`() {
        val photos = (1..5).map { photo(55.0, 37.0 + it * 0.0005, it * 500L) }
        val timeline = RouteVideoTimeline(track(photos), fps = 15, durationSec = 12, maxPhotos = 2)
        assertEquals(2, timeline.photoCount)
    }

    @Test
    fun `photo without coordinates falls back to its timestamp`() {
        val bare = TrackPhoto(trackId = 1, uri = "/tmp/p.jpg", timestamp = 3_000L, latitude = null, longitude = null)
        val timeline = RouteVideoTimeline(track(listOf(bare)), fps = 15, durationSec = 12, maxPhotos = 10)
        val start = (0 until timeline.totalFrames).first { timeline.photoAt(it) != null }
        assertEquals(0.75f, timeline.mapProgress(start), 0.02f)
    }
}