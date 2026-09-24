package com.alexander.routestory.video

import com.alexander.routestory.domain.Track
import com.alexander.routestory.domain.TrackPhoto
import com.alexander.routestory.domain.TrackPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.alexander.routestory.utils.GeoUtils

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
    internal val frameRate = fps
    /** Distance-based mapping used for both the moving marker and photo positions. */
    internal val motion = RouteVideoMotion(track.points)

    /** Whole map part (tilt + zoom + move); the encode frame cap controls total work. */
    val baseFrames: Int = (durationSec * fps).coerceAtLeast(2)

    /** Tilt-in after the intro: the marker stands still while the camera leans to 3D. */
    val tiltFrames: Int = (TILT_SEC * fps).roundToInt().coerceAtLeast(1)

    /** Navigator-style zoom onto the start marker before the ride begins moving. */
    val zoomFrames: Int = (ZOOM_SEC * fps).roundToInt().coerceAtLeast(1)
    /** Move frames: only here the marker rides. */
    fun moveFrames(): Int = (baseFrames - tiltFrames - zoomFrames).coerceAtLeast(1)

    /** Fixed-length intro before the map (title card). */
    val introFrames: Int = (INTRO_SEC * fps).roundToInt().coerceAtLeast(1)

    /** Fixed-length outro after the map (store line). */
    val outroFrames: Int = (OUTRO_SEC * fps).roundToInt().coerceAtLeast(1)

    /**
     * Sprint 35: cap for the total photo-pause duration in seconds. When many photos would
     * add more pause time than this, the per-photo hold is shrunk (never below 1.0 s) so a
     * photo-heavy export still finishes within a reasonable wall-clock encode window.
     *
     * Computed from the RAW [track.photos] count (coerced by [maxPhotos]) instead of the
     * [photos] property, which is only assigned in the init block AFTER property
     * initializers — using [photos] here would hit a NullPointerException.
     */
    private val budgetedPhotoHold: Float = run {
        val count = min(track.photos.size, maxPhotos.coerceAtLeast(0)).coerceAtLeast(0)
        if (count == 0) PHOTO_HOLD_SEC else {
            val budgetSec = max(PHOTO_PAUSE_BUDGET_SEC, durationSec * 0.5f)
            (budgetSec / count).coerceIn(MIN_PHOTO_HOLD_SEC, PHOTO_HOLD_SEC)
        }
    }

    /** Frames a single photo stays on screen (Sprint 35: 5 s default, shrunk by the budget). */
    val holdFrames: Int =
        (budgetedPhotoHold * fps).roundToInt()
            .coerceIn(2, (PHOTO_HOLD_SEC * fps).roundToInt())

    /** Sprint 35: per-photo hold actually used after the encode-budget reduction. */
    val effectivePhotoHoldSec: Float get() = budgetedPhotoHold

    private val fadeFrames: Int =
        (PHOTO_FADE_SEC * fps).roundToInt().coerceIn(1, holdFrames / 2)

    /**
     * Sprint 35: total frames all photo pauses add to the map part. Non-zero only
     * when photos are actually bound to the route (no coords -> no pause).
     */
    val photoPauseFrames: Int get() = photos.size * holdFrames

    /** Absolute frame the [index]-th photo insert starts at, or -1 when no insert. */
    fun photoStartFrame(index: Int): Int =
        if (index in photos.indices) introFrames + pauseStarts[index] else -1

    /**
     * Sprint 41: absolute frame where the marker reaches the speed peak.
     * Eagerly computed in init (before the boost-aware pause refinement),
     * because mapProgress() -> peakBoost() reads it every frame.
     */
    var peakFrame: Int = -1
        private set

    /** Peak-frame scan over raw (unboosted) progress; called once from init. */
    private fun scanPeakFrame(): Int {
        val c = peakFraction ?: return -1
        val target = warpMove(c)
        val last = introFrames + baseFrames + photoCount * holdFrames - 1
        var i = introFrames + tiltFrames + zoomFrames
        while (i < last && rawMapProgress(i) < target) i++
        return i
    }

    /**
     * Sprint 41: displayed progress of MOVE raw index [raw] as if photo [k]'s pause
     * started there (used by the boost-aware refinement of pauseStarts). Mirrors
     * mapProgress(): warp + peak boost, holds of photos before [k] already elapsed.
     */
    private fun boostedAt(raw: Int, k: Int): Float {
        val moveFrames = moveFrames().coerceAtLeast(1)
        val frame = introFrames + tiltFrames + zoomFrames + raw + k * holdFrames
        return peakBoostedProgress(raw / (moveFrames - 1).coerceAtLeast(1).toFloat(), frame)
    }

    /**
     * Sprint 40: marker progress WITHOUT the peak speedup (warp + photo pauses).
     * peakFrame scans this; mapProgress() adds the speedup on top.
     */
    private fun rawMapProgress(frame: Int): Float {
        if (isIntro(frame)) return 0f
        if (isOutro(frame)) return 1f
        val mapFrame = mapFrameOf(frame)
        if (mapFrame < tiltFrames + zoomFrames) return 0f
        val pause = pauseIndexAt(mapFrame)
        // Sprint 41: freeze on the PIN fraction itself (distance space), so the
        // frozen marker sits exactly where the pin is drawn (insertFraction).
        if (pause >= 0) return fractions[pause]
        var holds = 0
        for (i in pauseStarts.indices) if (mapFrame >= pauseStarts[i] + holdFrames) holds++
        val moveFrame = (mapFrame - tiltFrames - zoomFrames - holds * holdFrames)
            .coerceIn(0, moveFrames() - 1)
        val raw = moveFrame / (moveFrames() - 1).toFloat()
        return warpMove(raw)
    }

    private val photos: List<TrackPhoto>
    /** Route fraction (by distance) of each photo insert. */
    private val fractions: FloatArray
    private val pauseStarts: IntArray

    /** Distance fraction and speed of the fastest inner point; null when no clear peak. */
    val peakFraction: Float?
    val peakSpeedMps: Float

    /** Distance-weighted mean speed of the whole track (m/s); 0 when unknown. */
    val avgSpeedMps: Float

    /**
     * Sprint 43: peak boost speed cap. For tracks slower than the threshold (e.g. long
     * walks), the 1.5x peak speedup is tamed so the camera stays readable. Exposed as a
     * val so the renderer / tests can check the actual cap.
     */
    val peakBoostSpeedCap: Float

    init {
        val (fraction, speed) = findPeak(track.points)
        peakFraction = fraction
        peakSpeedMps = speed
        avgSpeedMps = averageSpeedMps(track)
        // Sprint 47: no global warp-time stretch anymore - slowness comes from a longer
        // durationSec (VideoLimits.fitEncode), so the marker keeps a single even tempo.
        // Peak boosts stay capped to keep the effect subtle.
        // Sprint 43: peak speedup cap - slower tracks get a gentler burst.
        peakBoostSpeedCap = PEAK_BOOST_SPEED *
            (if (avgSpeedMps < 8f) 0.6f else 1f) // 1.5x -> 0.9x for walking tracks
        val selected = track.photos
            .sortedBy { it.timestamp }
            .take(maxPhotos.coerceAtLeast(0))
            .map { it to fractionOf(it, track.points) }
            .sortedBy { it.second }
        photos = selected.map { it.first }
        fractions = FloatArray(selected.size) { selected[it].second }
        // Sprint 41: pause starts are planned so the photo opens exactly when the
        // marker's displayed DISTANCE fraction reaches the pin fraction - pass 1
        // finds the warp-only frame, then peakBoost's extra progress (< 1 frame)
        // is backed out. During the pause mapProgress() freezes on fractions[i]
        // itself, so the frozen marker and the pin are the same route point.
        pauseStarts = IntArray(selected.size) {
            tiltFrames + zoomFrames + rawMoveIndexFor(fractions[it]) + it * holdFrames
        }
        // ...then the peak boost (0.5 * step on top of warpMove) is accounted for by
        // walking the start BACK while the actual progress already reached the pin;
        // boost is only nonzero near peakFrame, so this shifts by <= ~1 frame.
        peakFrame = scanPeakFrame()
        var prevRaw = 0
        for (k in pauseStarts.indices) {
            val f = fractions[k]
            var raw = pauseStarts[k] - tiltFrames - zoomFrames - k * holdFrames
            if (raw < prevRaw) raw = prevRaw
            while (raw > prevRaw && boostedAt(raw - 1, k) >= f) raw--
            pauseStarts[k] = tiltFrames + zoomFrames + raw + k * holdFrames
            prevRaw = raw
        }
    }

    val photoCount: Int get() = photos.size

    /** Photos that actually get an insert, in route order (markers must match). */
    val inserts: List<TrackPhoto> get() = photos

    /**
     * Sprint 41: distance fraction of the i-th insert. The renderer draws the pin
     * from this value (same source as the frozen marker), so pin and marker can
     * never diverge through different geo-binding heuristics.
     */
    fun insertFraction(index: Int): Float = fractions.getOrElse(index) { 0f }

    val totalFrames: Int get() = introFrames + baseFrames + photoCount * holdFrames + outroFrames

    /** True while [frame] belongs to the intro title card. */
    fun isIntro(frame: Int): Boolean = frame < introFrames

    /** True when [frame] belongs to either map zoom-out or the final outro card. */
    fun isOutro(frame: Int): Boolean = frame >= outroStartFrame

    val outroStartFrame: Int get() = introFrames + baseFrames + photoCount * holdFrames

    /** First OUTRO_ZOOM_SEC renders the map; later frames render the card. */
    fun isOutroCard(frame: Int): Boolean = frame >= outroStartFrame + outroZoomFrames

    val outroZoomFrames: Int = (OUTRO_ZOOM_SEC * fps).roundToInt()
        .coerceIn(1, (outroFrames - 1).coerceAtLeast(1))

    /** Smooth 0..1 map zoom-to-overview during the transition. */
    fun outroZoomOut(frame: Int): Float {
        if (frame < outroStartFrame) return 0f
        val t = ((frame - outroStartFrame).toFloat() / outroZoomFrames.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Map-local frame (0-based from map start, after intro). */
    private fun mapFrameOf(frame: Int): Int = frame - introFrames

    /** 0..1 tilt ramp over [tiltFrames]; marker stands still while < 1. */
    fun tiltAmount(frame: Int): Float {
        if (isIntro(frame)) return 0f
        if (isOutro(frame)) return 1f
        val mf = mapFrameOf(frame)
        if (mf <= 0) return 0f
        if (mf >= tiltFrames) return 1f
        val t = mf.toFloat() / tiltFrames
        return t * t * (3 - 2 * t)
    }

    /** 0..1 zoom ramp over [zoomFrames] right after tilt; marker still stands. */
    fun zoomAmount(frame: Int): Float {
        if (isIntro(frame)) return 0f
        if (isOutro(frame)) return 1f
        val mf = mapFrameOf(frame) - tiltFrames
        if (mf <= 0) return 0f
        if (mf >= zoomFrames) return 1f
        val t = mf.toFloat() / zoomFrames
        return t * t * (3 - 2 * t)
    }

    /**
     * Sprint 34: smooth camera-follow blend, replacing the binary isMarkerHeld.
     * 0 at tilt end -> 1 across the ZOOM phase (camera glides onto the start marker),
     * stays 1 through MOVE (camera is always on the marker).
     */
    fun followBlend(frame: Int): Float {
        if (isIntro(frame)) return 0f
        if (isOutro(frame)) return 1f
        val mf = mapFrameOf(frame)
        if (mf <= tiltFrames) return 0f
        return zoomAmount(frame)
    }

    /**
     * Sprint 41: route progress (0..1 by distance) the map shows at [frame].
     * Marker freezes on fractions[i] (the pin itself) during its pause; the pause
     * starts on the frame the displayed progress first reaches that fraction, so
     * the full-screen photo begins exactly when the marker lands on the pin.
     * The peak window plays ~1.5x faster on top of the warp (time-warp only,
     * still monotonic, still 0..1).
     */
    fun mapProgress(frame: Int): Float {
        if (isIntro(frame)) return 0f
        if (isOutro(frame)) return 1f
        val mapFrame = mapFrameOf(frame)
        // TILT + ZOOM: marker stands at the start.
        if (mapFrame < tiltFrames + zoomFrames) return 0f
        val pause = pauseIndexAt(mapFrame)
        // Sprint 41: the marker is frozen ON the pin fraction for the whole hold.
        if (pause >= 0) return fractions[pause]
        var holds = 0
        for (i in pauseStarts.indices) if (mapFrame >= pauseStarts[i] + holdFrames) holds++
        val moveFrame = (mapFrame - tiltFrames - zoomFrames - holds * holdFrames)
            .coerceIn(0, moveFrames() - 1)
        val raw = moveFrame / (moveFrames() - 1).toFloat()
        // Same warp path as rawMapProgress, plus the peak speedup.
        return peakBoostedProgress(raw, frame)
    }

    /**
     * Sprint 40: ~1.5x marker speedup inside the peak window (Sprint 43: capped to
     * [peakBoostSpeedCap] on slow/walking tracks so the burst stays subtle).
     * Adds (cap-1) * boost(frame) * frameStep to the warped
     * progress, then clamps: monotonic, 0..1, and exactly 0/1 at the ends.
     * Photo pauses bypass it (marker is frozen there anyway).
     */
    private fun peakBoostedProgress(raw: Float, frame: Int): Float {
        val base = warpMove(raw)
        val b = peakBoost(frame)
        if (b <= 0f) return base
        val step = 1f / moveFrames().coerceAtLeast(1)
        return (base + (peakBoostSpeedCap - 1f) * b * step).coerceIn(0f, 1f)
    }

    /**
     * Even tempo warp, strictly monotonic, warp(0)=0, warp(1)=1:
     *
     * Sprint 47: the old three-band global slow (slow middle + fast compensating
     * edges) is gone - it produced the "fast-slow-fast" marker on short rides.
     * Global slowness now comes from a longer durationSec (VideoLimits), so the raw
     * progress maps 1:1 and only the local peak slow-mo window bends it (identity
     * when the track has no clear speed peak).
     */
    fun warpMove(raw: Float): Float {
        val r = raw.coerceIn(0f, 1f)
        val moveSec = moveFrames().toFloat() / frameRate.coerceAtLeast(1)
        val c = peakFraction
        val h = if (c != null) (PEAK_HALF_SEC / moveSec).coerceIn(0f, 0.25f) else 0f
        val cc = (c ?: 0f).coerceIn(0f, 1f)
        return peakWarp(r, h, cc)
    }

    /** Original peak-only warp (no global slow): monotonic, 0->0, 1->1. */
    private fun peakWarp(r: Float, h: Float, cc: Float): Float {
        val s = PEAK_SLOW
        val denom = (1f - 2f * h).coerceAtLeast(0.001f)
        val s1 = (1f - s * 2f * h) / denom
        return when {
            r < cc - h -> r * s1
            r < cc + h -> (cc - h) * s1 + (r - (cc - h)) * s
            else -> (cc - h) * s1 + 2f * h * s + (r - cc - h) * s1
        }.coerceIn(0f, 1f)
    }
    /** Photo shown full screen at [frame], or null while the map is visible. */
    fun photoAt(frame: Int): TrackPhoto? {
        if (isIntro(frame) || isOutro(frame)) return null
        return pauseIndexAt(frame - introFrames).let { if (it >= 0) photos[it] else null }
    }

    /** Fade in/out envelope (<= [PHOTO_FADE_SEC]) of the full-screen photo at [frame]. */
    fun photoAlpha(frame: Int): Float {
        if (isIntro(frame) || isOutro(frame)) return 0f
        val pause = pauseIndexAt(frame - introFrames)
        if (pause < 0) return 0f
        val local = frame - introFrames - pauseStarts[pause]
        return (min(local + 1, holdFrames - local).toFloat() / fadeFrames).coerceIn(0f, 1f)
    }

    /** Fade-in of the intro card (<= CARD_FADE_SEC); reaches 1.0 quickly and stays. */
    fun introAlpha(frame: Int): Float =
        ((frame + 1).toFloat() / (CARD_FADE_SEC * frameRate).roundToInt().coerceAtLeast(1)).coerceIn(0f, 1f)

    /** Fade-in of the outro card (<= CARD_FADE_SEC). */
    fun outroAlpha(frame: Int): Float =
        ((frame - (totalFrames - outroFrames) + 1).toFloat() /
            (CARD_FADE_SEC * frameRate).roundToInt().coerceAtLeast(1)).coerceIn(0f, 1f)

    /** Index of the point the marker sits on at [frame]; for logging and tests. */
    fun pointIndex(progress: Float): Int = motion.position(progress).toInt().coerceAtLeast(0)

    /**
     * True when [frame] renders the exact same full-screen photo as [frame]-1 (static
     * middle of a photo pause, past the fade edges). The export loop can then repeat
     * the already-rendered picture with a new presentation time instead of re-rendering
     * and re-converting an identical frame — photo pauses are up to 25% of all frames.
     */
    fun isStaticPhotoFrame(frame: Int): Boolean =
        photoAt(frame) != null && photoAlpha(frame) >= 1f

    /**
     * Sprint 41: raw MOVE index (0..moveFrames()-1) whose warped display first
     * reaches [fraction] itself (the pin's distance fraction). Binary search over
     * the monotonic [warpMove]; peak boost is backed out afterwards in init.
     */
    private fun rawMoveIndexFor(fraction: Float): Int {
        val last = (moveFrames() - 1).coerceAtLeast(1)
        // Sprint 41: the pin lives in DISTANCE space - find the first raw frame whose
        // warped display reaches the fraction itself (was warpMove(fraction), which
        // opened the photo off-pin whenever warp != identity).
        val target = fraction.coerceIn(0f, 1f)
        var lo = 0
        var hi = last
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (warpMove(mid / last.toFloat()) < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun pauseIndexAt(frame: Int): Int =
        pauseStarts.indexOfFirst { frame >= it && frame < it + holdFrames }

    /**
     * Sprint 40: 0..1 detail burst around the speed peak frame.
     * 1 at the peak frame, ramps to 0 over PEAK_BOOST_HALF_SEC each side.
     * Drives both the extra camera zoom and the ~1.5x marker speedup.
     */
    fun peakBoost(frame: Int): Float {
        val pf = peakFrame
        if (pf < 0 || photoAt(frame) != null) return 0f
        val half = (PEAK_BOOST_HALF_SEC * frameRate).coerceAtLeast(1f)
        return (1f - kotlin.math.abs(frame - pf) / half).coerceIn(0f, 1f)
    }

    /** True when the marker is on the speed peak (±[PEAK_LABEL_HALF_SEC] of MOVE time). */
    fun isPeakActive(frame: Int): Boolean {
        val c = peakFraction ?: return false
        val target = warpMove(c)
        val p = mapProgress(frame)
        // Warp-space window scaled back to ~±1.5 s of raw MOVE progress.
        val moveSec = moveFrames().toFloat() / frameRate.coerceAtLeast(1)
        if (moveSec <= 0f) return false
        val w = (PEAK_LABEL_HALF_SEC / moveSec * 0.45f).coerceAtMost(0.25f)
        return kotlin.math.abs(p - target) <= w && photoAt(frame) == null
    }

    /**
     * Photo binding: EXIF timestamp -> interpolated route position first (primary);
     * explicit GPS coordinates only as a fallback. Never excluded for missing coords.
     */
    private fun fractionOf(photo: TrackPhoto, points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        // Sprint 40: bind to the exact polyline point (position -> distance fraction),
        // NOT to the nearest GPS fix. The renderer draws the pin at the same
        // interpolateByTime position, so the frozen marker lands on the pin itself
        // instead of up to half a sample away (1 s of GPS logging).
        val geo = GeoUtils.interpolateByTime(points, photo.timestamp)
        if (geo != null) return motion.fractionAtPosition(motion.positionOf(geo.first, geo.second))
        val lat = photo.latitude
        val lon = photo.longitude
        if (lat != null && lon != null) {
            return motion.fractionAtPosition(motion.positionOf(lat, lon))
        }
        val idx = points.indexOfLast { it.timestamp <= photo.timestamp }.coerceAtLeast(0)
        return motion.fractionAtPosition(idx.toFloat())
    }

    companion object {
        /**
         * Full-screen photo insert: 1 s fade-in + 3 s hold + 1 s fade-out (= 5 s).
         * Envelope: PHOTO_FADE_SEC on each edge of PHOTO_HOLD_SEC, held flat in between.
         */
        const val PHOTO_HOLD_SEC = 5.0f
        const val PHOTO_FADE_SEC = 1.0f
        /**
         * Sprint 35: wall-clock cap on the TOTAL photo-pause duration. When many photos
         * would add more pause time than this, the per-photo hold shrinks (never below
         * [MIN_PHOTO_HOLD_SEC]) so photo-heavy exports still finish in reasonable time.
         */
        const val PHOTO_PAUSE_BUDGET_SEC = 60f
        /** Sprint 35: floor of the per-photo hold after the budget reduction. */
        const val MIN_PHOTO_HOLD_SEC = 1.0f
        /** Title cards, trimmed so static frames don't eat the encode budget. */
        const val INTRO_SEC = 4f
        const val OUTRO_SEC = 2f
        /** First second of OUTRO is a live map zoom-out; the rest is the existing card. */
        const val OUTRO_ZOOM_SEC = 1f
        /** TILT + ZOOM intros before MOVE: marker stands still. */
        const val TILT_SEC = 2f
        const val ZOOM_SEC = 2f
        /** Peak label/pulse window: shown only while the marker is on the peak. */
        const val PEAK_LABEL_HALF_SEC = 1.5f
        /** Card fade-in cap: every intro/outro effect stays under a quarter second. */
        const val CARD_FADE_SEC = 0.25f
        /** Sprint 40: detail burst at the speed peak (~0.5 s each side). */
        const val PEAK_BOOST_HALF_SEC = 0.5f
        /** Sprint 40: extra marker speed inside the peak window (~1.5x). */
        const val PEAK_BOOST_SPEED = 1.5f
        /** Sprint 40: extra camera zoom inside the peak window. */
        const val PEAK_BOOST_ZOOM = 0.35f
        /** Slow-mo half-window around the speed peak. */
        const val PEAK_HALF_SEC = 1.25f
        /** Slope multiplier inside the peak slow-mo window. */
        const val PEAK_SLOW = 0.45f
        /**
         * Average speed of the whole track in m/s. Uses the GPS-reported
         * [TrackPoint.speed] values (distance-weighted: each point's speed
         * contributes proportionally to the distance it covers), falling
         * back to total-distance / total-duration when speeds are all zero.
         * Returns 0 when there are fewer than 2 points.
         */
        fun averageSpeedMps(track: Track): Float {
            val pts = track.points
            if (pts.size < 2) return 0f
            // Primary: distance-weighted average of the per-point speed field.
            // Each point's speed is weighted by the distance from the previous point.
            var weighted = 0f
            var totalDist = 0f
            for (i in 1 until pts.size) {
                val d = GeoUtils.distanceMeters(pts[i - 1], pts[i])
                if (d > 0f) {
                    weighted += pts[i].speed * d
                    totalDist += d
                }
            }
            if (totalDist > 0f && weighted > 0f) return weighted / totalDist
            // Fallback: total distance / total duration (when speed field is all zeros).
            if (totalDist > 0f && track.duration > 0L) return totalDist / (track.duration / 1000f)
            return 0f
        }

        /** Peak label/pulse window: shown only while the marker is on the peak. */


        /** Peak = max smoothed speed in the 20–80% window, >= max(1.5*avg, 5 m/s). */
        fun findPeak(points: List<TrackPoint>): Pair<Float?, Float> {
            if (points.size < 4) return null to 0f
            val sm = FloatArray(points.size) { i ->
                val a = (i - 1).coerceAtLeast(0)
                val b = (i + 1).coerceAtMost(points.size - 1)
                var s = 0f
                var n = 0
                for (k in a..b) { s += points[k].speed; n++ }
                s / n
            }
            var avg = 0f
            for (v in sm) avg += v
            avg /= sm.size
            val from = (points.size * 0.2f).toInt().coerceAtLeast(1)
            val to = (points.size * 0.8f).toInt().coerceAtMost(points.size - 2)
            if (to <= from) return null to 0f
            var best = from
            for (i in from..to) if (sm[i] > sm[best]) best = i
            val peak = sm[best]
            if (peak < maxOf(1.5f * avg, 5f)) return null to 0f
            val m = RouteVideoMotion(points)
            return m.fractionAt(best) to points[best].speed
        }
    }
}
