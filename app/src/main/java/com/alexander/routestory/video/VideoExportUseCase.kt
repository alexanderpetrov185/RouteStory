package com.alexander.routestory.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.alexander.routestory.domain.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encodes an animated route video fully on-device:
 * Canvas frames -> RGBA to YUV420 -> MediaCodec (H.264) -> MediaMuxer (MP4).
 */
@Singleton
class VideoExportUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun export(track: Track, limits: VideoLimits, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.Default) {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var outFile: File? = null
        var success = false
        try {
            val dir = File(context.filesDir, "videos").apply { mkdirs() }
            pruneOldVideos(dir)
            val dest = File(dir, "track_" + track.id + "_" + System.currentTimeMillis() + ".mp4")
            outFile = dest
            val renderer = RouteFrameRenderer(limits)
            val frameCount = limits.durationSec * limits.fps
            val bitmap = Bitmap.createBitmap(limits.width, limits.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, limits.width, limits.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_FRAME_RATE, limits.fps)
                setInteger(MediaFormat.KEY_BIT_RATE, limits.bitrate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var videoTrack = -1
            var muxerStarted = false
            var eosSent = false
            var eosReceived = false
            var frame = 0
            val info = MediaCodec.BufferInfo()

            while (!eosReceived) {
                if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("Video export cancelled")

                if (frame < frameCount && !eosSent) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val image = codec.getInputImage(inIndex)
                        if (image != null) {
                            renderer.render(track, frame, frameCount, canvas)
                            copyBitmapToImage(bitmap, image)
                            val ptsUs = frame * 1_000_000L / limits.fps
                            val isLast = frame == frameCount - 1
                            val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            codec.queueInputBuffer(inIndex, 0, limits.width * limits.height * 3 / 2, ptsUs, flags)
                            if (isLast) eosSent = true
                            frame++
                            onProgress(frame.toFloat() / frameCount)
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(info, 10_000L)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = mux.addTrack(codec.outputFormat)
                        mux.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val encoded = codec.getOutputBuffer(outIndex)
                        if (encoded != null && info.size > 0 && muxerStarted) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (info.size > 0) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                mux.writeSampleData(videoTrack, encoded, info)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            eosReceived = true
                            break
                        }
                    } else {
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(info, 10_000L)
                }
            }

            mux.stop()
            success = true
            dest
        } catch (e: kotlinx.coroutines.CancellationException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            if (!success) outFile?.delete()
        }
    }

    private fun copyBitmapToImage(bitmap: Bitmap, image: Image) {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        fillPlane(yPlane.buffer, yPlane.pixelStride, yPlane.rowStride, pixels, w, h, 0)
        fillPlane(uPlane.buffer, uPlane.pixelStride, uPlane.rowStride, pixels, w, h, 1)
        fillPlane(vPlane.buffer, vPlane.pixelStride, vPlane.rowStride, pixels, w, h, 2)
    }

    /** component: 0 = Y, 1 = U, 2 = V (BT.601 limited range). */
    private fun fillPlane(buffer: java.nio.ByteBuffer, pixelStride: Int, rowStride: Int, pixels: IntArray, w: Int, h: Int, component: Int) {
        for (row in 0 until if (component == 0) h else h / 2) {
            for (col in 0 until if (component == 0) w else w / 2) {
                val px: Int = if (component == 0) {
                    pixels[row * w + col]
                } else {
                    pixels[(row * 2) * w + (col * 2)]
                }
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val value: Int = when (component) {
                    0 -> ((16829 * r + 33039 * g + 6416 * b) shr 16) + 16
                    1 -> ((-9714 * r - 19071 * g + 28784 * b) shr 16) + 128
                    else -> ((28784 * r - 24103 * g - 4681 * b) shr 16) + 128
                }
                buffer.put(row * rowStride + col * pixelStride, value.coerceIn(0, 255).toByte())
            }
        }
    }

    /** Keeps only the newest videos so storage does not grow indefinitely. */
    private fun pruneOldVideos(dir: File, keep: Int = 4) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(keep).forEach { it.delete() }
    }
}
