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
        private const val FREE_DURATION_SEC = 12
        private const val PRO_DURATION_SEC = 60
        private const val FREE_MAX_PHOTOS = 6
        /** Free tier is capped to 360p/15fps/12s/1.5Mbps with a watermark and up to 6 photos —
         *  tuned so a weak phone renders the clip in well under a minute.
         *  Pro tier is 720p/30fps/60s/5Mbps with no watermark and unlimited photos. */
        fun resolve(isPro: Boolean): VideoLimits =
            if (isPro) {
                VideoLimits(PRO_DURATION_SEC, 1280, 720, 30, 5_000_000, null, Int.MAX_VALUE)
            } else {
                VideoLimits(FREE_DURATION_SEC, 640, 360, 15, 1_500_000, "RouteStory", FREE_MAX_PHOTOS)
            }
    }
}