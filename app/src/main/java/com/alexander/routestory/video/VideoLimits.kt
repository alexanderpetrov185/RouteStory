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
         * Fixed blocks outside the selected marker MOVE: intro 4 + map tilt 2 +
         * camera zoom 2 + finish 5 + outro 2. Photo holds are additional.
         */
        const val FIXED_NON_MOVE_SEC = 15
        /** Auto and explicit map-movement budget both stop at two minutes. */
        const val MAX_MAP_MOVE_SEC = 120
        /** Total photo-pause budget; per-photo hold never exceeds 5 s. */
        private const val PHOTO_PAUSE_BUDGET_SEC = 60f
        private const val MAX_PHOTO_HOLD_SEC = 5f
        private const val MIN_PHOTO_HOLD_SEC = 1f

        /**
         * Chosen [targetMapSec] is the clean MOVE time in which the marker travels.
         * VideoLimits.durationSec includes the four stationary map-camera seconds, while
         * intro/finish/outro and actual photo holds remain additional to that value.
         */
        fun fitEncode(
            isPro: Boolean,
            distanceMeters: Float,
            photoCount: Int,
            targetMapSec: Int? = null
        ): VideoLimits {
            val base = resolve(isPro, distanceMeters)
            val cameraIntroSec = 4 // TILT_SEC + ZOOM_SEC from RouteVideoTimeline.
            fun photoPauseSec(durationSec: Int): Int {
                val count = minOf(photoCount, base.maxPhotos).coerceAtLeast(0)
                if (count == 0) return 0
                val hold = (maxOf(PHOTO_PAUSE_BUDGET_SEC, durationSec * 0.5f) / count)
                    .coerceIn(MIN_PHOTO_HOLD_SEC, MAX_PHOTO_HOLD_SEC)
                return (hold * count).toInt()
            }
            fun totalFrames(mapSec: Int, f: Int): Int {
                val durationSec = mapSec + cameraIntroSec
                return (durationSec + FIXED_NON_MOVE_SEC - cameraIntroSec +
                    photoPauseSec(durationSec)) * f
            }

            val requestedMapSec = if (targetMapSec != null) {
                targetMapSec.coerceIn(MIN_ENCODE_SEC, MAX_MAP_MOVE_SEC)
            } else {
                base.durationSec.coerceIn(MIN_ENCODE_SEC, MAX_MAP_MOVE_SEC)
            }
            var fps = base.fps
            var mapSec = requestedMapSec
            while (totalFrames(mapSec, fps) > MAX_ENCODE_FRAMES && fps > MIN_ENCODE_FPS) fps--
            // Explicit choices keep their marker MOVE time; auto may also shorten the
            // map part so fixed cards + photos do not grow without a controlled budget.
            if (targetMapSec == null) {
                while (totalFrames(mapSec, fps) > MAX_ENCODE_FRAMES &&
                    mapSec > MIN_ENCODE_SEC) mapSec--
            }
            val durationSec = mapSec + cameraIntroSec
            return if (durationSec == base.durationSec && fps == base.fps) base
            else base.copy(durationSec = durationSec, fps = fps)
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