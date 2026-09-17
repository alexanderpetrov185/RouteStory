package com.alexander.routestory.video

import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackPoint
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Timeline of the exported clip.
 *
 * The route animation always spans the entire clip (progress 0 -> 1 measured by distance, see
 * [RouteVideoMotion]); every photo inserts a short full-screen pause exactly where it was taken.
 * Photos are ordered by their position along the route (not by wall clock), so a shot taken
 * while the GPS was paused still lines up with the marker the user sees on the map.
 */
class RouteVideoTimeline(
    track: Track,
    fps: Int,
    durationSec: Int,
    maxPhotos: Int
) {
    /** Distance-based mapping used for both the moving marker and photo positions. */
    internal val motion = RouteVideoMotion(track.points)

    /** Frames of pure map animation (route always runs through all of them). */
    val baseFrames: Int = (durationSec * fps).coerceAtLeast(2)

    /** Frames a single photo stays on screen, including its fades. */
    val holdFrames: Int = (PHOTO_HOLD_SEC * fps).roundToInt().coerceAtLeast(2)

    private val fadeFrames: Int = (PHOTO_FADE_SEC * fps).roundToInt().coerceIn(1, holdFrames / 2)

    private val photos: List<TrackPhoto>
    /** Route fraction (by distance) of each photo insert. */
    private val fractions: FloatArray
    private val pauseStarts: IntArray

    init {
        val selected = track.photos
            .sortedBy { it.timestamp }
            .take(maxPhotos.coerceAtLeast(0))
            .map { it to fractionOf(it, track.points) }
            .sortedBy { it.second }
        photos = selected.map { it.first }
        fractions = FloatArray(selected.size) { selected[it].second }
        pauseStarts = IntArray(selected.size) {
            (fractions[it] * (baseFrames - 1)).roundToInt() + it * holdFrames
        }
    }

    val photoCount: Int get() = photos.size

    /** Photos that actually get an insert, in route order (markers must match). */
    val inserts: List<TrackPhoto> get() = photos

    val totalFrames: Int get() = baseFrames + photoCount * holdFrames

    /** Route progress (0..1 by distance) the map should show at [frame]. */
    fun mapProgress(frame: Int): Float {
        val pause = pauseIndexAt(frame)
        if (pause >= 0) return fractions[pause]
        var holds = 0
        for (i in pauseStarts.indices) if (frame >= pauseStarts[i] + holdFrames) holds++
        val anim = (frame - holds * holdFrames).coerceIn(0, baseFrames - 1)
        return anim / (baseFrames - 1).toFloat()
    }

    /** Photo shown full screen at [frame], or null while the map is visible. */
    fun photoAt(frame: Int): TrackPhoto? = pauseIndexAt(frame).let { if (it >= 0) photos[it] else null }

    /** Fade in/out envelope (<= [PHOTO_FADE_SEC]) of the full-screen photo at [frame]. */
    fun photoAlpha(frame: Int): Float {
        val pause = pauseIndexAt(frame)
        if (pause < 0) return 0f
        val local = frame - pauseStarts[pause]
        return (min(local + 1, holdFrames - local).toFloat() / fadeFrames).coerceIn(0f, 1f)
    }

    /** Index of the point the marker sits on at [frame]; for logging and tests. */
    fun pointIndex(progress: Float): Int = motion.position(progress).toInt().coerceAtLeast(0)

    private fun pauseIndexAt(frame: Int): Int =
        pauseStarts.indexOfFirst { frame >= it && frame < it + holdFrames }

    /** Distance fraction of the route point nearest to the photo. */
    private fun fractionOf(photo: TrackPhoto, points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        val lat = photo.latitude
        val lon = photo.longitude
        val index = if (lat != null && lon != null) {
            var best = 0
            var bestD = Double.MAX_VALUE
            points.forEachIndexed { i, p ->
                val d = abs(p.lat - lat) + abs(p.lon - lon)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            best
        } else {
            points.indexOfLast { it.timestamp <= photo.timestamp }.coerceAtLeast(0)
        }
        return motion.fractionAt(index)
    }

    private companion object {
        /** Full-screen photo duration; long enough to read, short enough for a 15 fps clip. */
        const val PHOTO_HOLD_SEC = 2.6f
        /** Fade in/out envelope. 0.2 s keeps it snappy (3 frames at 15 fps, 6 at 30 fps). */
        const val PHOTO_FADE_SEC = 0.2f
    }
}