package com.alexander.routestory.video

/**
 * Video limits. A single source of truth for free and Pro tiers.
 * Pro: no watermark, longer video, all photos.
 */
data class VideoLimits(
    val durationSec: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val watermark: String?,
    val maxPhotos: Int
) {
    companion object {
        private const val FREE_MAX_PHOTOS = 10
        private const val PRO_MAX_PHOTOS = 12
        /** Encode budget: frame count dominates wall-clock (CPU YUV per pixel). */
        private const val FREE_MIN_SEC = 12
        private const val FREE_MAX_SEC = 90
        private const val PRO_MIN_SEC = 15
        private const val PRO_MAX_SEC = 90
        /**
         * Sprint 47: distance -> duration tempo, ~2.5x slower marker than the old
         * 1000/800 mps. Long rides fill up to the cap; [fitEncode] keeps the total
         * inside [MAX_ENCODE_FRAMES], so baseFrames can never blow up the encode.
         */
        private const val FREE_VISUAL_MPS = 400f
        private const val PRO_VISUAL_MPS = 320f
        private const val MAP_BASE_SEC = 10
        private const val PRO_MAP_BASE_SEC = 15
        /**
         * Free: 736x414 / 12 fps / map 12..90 s / 1.9 Mbps, watermark, up to 10 photos.
         * Pro: 1280x720 / 15 fps / map 15..90 s / 5 Mbps, no watermark, up to 12 inserts.
         */
        fun resolve(isPro: Boolean, distanceMeters: Float = 0f): VideoLimits {
            val mapSec = mapDurationSec(distanceMeters, isPro)
            return if (isPro) {
                VideoLimits(mapSec, 1280, 720, 15, 5_000_000, null, PRO_MAX_PHOTOS)
            } else {
                VideoLimits(mapSec, 736, 414, 12, 1_900_000, "RouteStory", FREE_MAX_PHOTOS)
            }
        }

        /**
         * Sprint 41: hard cap on TOTAL frames (map + intro/outro + photo holds).
         * ~1200 frames keeps even a long photo-heavy export to a few minutes of
         * encode instead of ~20; the caller shrinks durationSec/fps until it fits.
         */
        const val MAX_ENCODE_FRAMES = 1200

        /** Floors so shrinking to the frame cap never degrades below usability. */
        const val MIN_ENCODE_SEC = 12
        const val MIN_ENCODE_FPS = 10

        /**
         * Sprint 41: choose (fps, durationSec) whose timeline fits [MAX_ENCODE_FRAMES].
         * Sprint 47 shrink order: fps first, then map seconds - durationSec carries the
         * marker tempo (long rides must stay slow), fps gives up to [MIN_ENCODE_FPS].
         * Photo holds are already budgeted inside RouteVideoTimeline
         * (PHOTO_PAUSE_BUDGET_SEC).
         */
        fun fitEncode(isPro: Boolean, distanceMeters: Float, photoCount: Int): VideoLimits {
            val base = resolve(isPro, distanceMeters)
            var fps = base.fps
            var sec = base.durationSec
            fun totalFrames(s: Int, f: Int): Int {
                val introOutro = ((4f + 2f) * f).toInt()
                val map = s * f
                // Upper bound of photo pauses: budgeted hold is <= 5 s per photo and
                // total pause budget is max(60 s, 50% of map); use the worst case.
                val photoSec = minOf(photoCount * 5f, maxOf(60f, s * 0.5f))
                return introOutro + map + (photoSec * f).toInt()
            }
            while (totalFrames(sec, fps) > MAX_ENCODE_FRAMES && fps > MIN_ENCODE_FPS) fps--
            while (totalFrames(sec, fps) > MAX_ENCODE_FRAMES && sec > MIN_ENCODE_SEC) sec--
            return if (sec == base.durationSec && fps == base.fps) base
            else base.copy(durationSec = sec, fps = fps)
        }

        /**
         * Map-part length from ride distance, clamped to the tier cap.
         * A 40 km ride hits the 90 s cap on both tiers (slower marker, Sprint 47).
         */
        fun mapDurationSec(distanceMeters: Float, isPro: Boolean): Int {
            val (min, max) = if (isPro) PRO_MIN_SEC to PRO_MAX_SEC else FREE_MIN_SEC to FREE_MAX_SEC
            val base = if (isPro) PRO_MAP_BASE_SEC else MAP_BASE_SEC
            val visual = if (isPro) PRO_VISUAL_MPS else FREE_VISUAL_MPS
            return (base + distanceMeters / visual).toInt().coerceIn(min, max)
        }
    }
}