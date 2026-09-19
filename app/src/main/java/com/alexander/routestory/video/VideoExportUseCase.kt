package com.alexander.routestory.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.alexander.routestory.domain.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encodes an animated route video fully on-device:
 * Canvas frames -> RGBA to YUV420 -> MediaCodec (H.264) -> MediaMuxer (MP4).
 * A light royalty-free background loop (res/raw/bgm.wav) is AAC-encoded first and
 * interleaved into the same MP4; any audio failure keeps the silent video.
 */
@Singleton
class VideoExportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotProvider: MapSnapshotProvider
) {

    suspend fun export(track: Track, limits: VideoLimits, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.Default) {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var outFile: File? = null
        var success = false
        var background: Bitmap? = null
        try {
            // Nothing meaningful to animate; do not create a codec or an empty file.
            if (track.points.isEmpty()) {
                Log.e("VideoExport", "export skipped: track has no points")
                return@withContext null
            }
            Log.i("VideoExport", "start points=${track.points.size}")
            val dir = File(context.filesDir, "videos").apply { mkdirs() }
            pruneOldVideos(dir)
            val dest = File(dir, "track_" + track.id + "_" + System.currentTimeMillis() + ".mp4")
            outFile = dest
            val snapshotStart = android.os.SystemClock.elapsedRealtime()
            background = withTimeoutOrNull(12_000L) {
                runCatching { snapshotProvider.snapshot(track, limits.width, limits.height) }
                    .onFailure { Log.e("VideoExport", "snapshot failed", it) }
                    .getOrNull()
            }
            val snapshotMs = android.os.SystemClock.elapsedRealtime() - snapshotStart
            Log.i(
                "VideoExport",
                "snapshot done in $snapshotMs ms " +
                    "background=${background != null}"
            )
            if (background == null) {
                Log.e("VideoExport", "snapshot failed: no background bitmap produced")
            }
            val encodeStart = android.os.SystemClock.elapsedRealtime()
            // Sprint 27: the BGM track is optional. Every audio step below is guarded, so a
            // failing encode or mux only leaves the MP4 silent — the export stays successful.
            val bgm: EncodedAudio? = encodeBgmAac()
            Log.i("VideoExport", "bgm samples=${bgm?.samples?.size ?: 0}")
            val renderer = RouteFrameRenderer(limits)
            // The timeline owns frame counting: map animation plus a pause for every photo.
            val timeline = RouteVideoTimeline(track, limits.fps, limits.durationSec, limits.maxPhotos)
            val frameCount = timeline.totalFrames
            Log.i("VideoExport",
                "timeline totalFrames=$frameCount " +
                "${limits.width}x${limits.height}@${limits.fps}fps " +
                "photos=${timeline.photoCount}")
            // One reusable frame bitmap: no per-frame allocations on weak devices.
            val bitmap = Bitmap.createBitmap(limits.width, limits.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Y-plane scratch reused for every frame (biggest allocation in the loop).
            val yRow = IntArray(limits.width)
            val uvRow = IntArray(limits.width / 2)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, limits.width, limits.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, limits.fps)
                setInteger(MediaFormat.KEY_BIT_RATE, limits.bitrate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            // Colour correctness (Sprint 15): prefer the codec-provided Image planes —
            // the encoder itself declares the plane layout, which avoids the dark-green
            // cast of hand-rolled NV12 on encoders that expect padded/planar input.
            // The NV12 byte-buffer path stays as fallback when getInputImage returns null.
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var videoTrack = -1
            var audioTrack = -1
            var muxerStarted = false
            var eosSent = false
            var eosReceived = false
            var frame = 0
            val info = MediaCodec.BufferInfo()
            // Interleaving state for the BGM samples (kept for the no-loop fallback).
            var audioNext = 0
            // Set when the muxer rejects an audio sample: the video keeps encoding and the clip
            // simply stays silent instead of failing the whole export.
            var audioBroken = false
            var lastAudioPtsUs = -1L
            fun writeAudioUpTo(ptsUs: Long) {
                if (bgm == null || audioTrack < 0 || audioBroken) return
                val samples = bgm.samples
                if (samples.isEmpty()) return
                // Audio never runs past the video timeline: samples written after the last
                // video frame are what used to make the muxer throw.
                if (ptsUs <= lastAudioPtsUs) return
                fun write(data: ByteArray, pts: Long) {
                    if (pts <= lastAudioPtsUs) return
                    val out = java.nio.ByteBuffer.wrap(data)
                    val ai = MediaCodec.BufferInfo()
                    ai.set(0, data.size, pts, 0)
                    mux.writeSampleData(audioTrack, out, ai)
                    lastAudioPtsUs = pts
                }
                try {
                    val totalUs = samples.last().ptsUs
                    if (totalUs <= 0L) {
                        while (audioNext < samples.size && samples[audioNext].ptsUs <= ptsUs) {
                            val s = samples[audioNext]
                            write(s.data, s.ptsUs)
                            audioNext++
                        }
                        return
                    }
                    // Loop the bundled BGM until the video ends: shift whole-loop copies
                    // forward by totalUs so pts stays monotonic.
                    var loop = 0L
                    while (true) {
                        var advanced = false
                        for (s in samples) {
                            val shifted = s.ptsUs + loop * totalUs
                            if (shifted > ptsUs) return
                            write(s.data, shifted)
                            advanced = true
                        }
                        if (!advanced) return
                        loop++
                        if (loop > 64) return // safety: ~hours of audio, never loop forever
                    }
                } catch (e: Exception) {
                    // Optional track: log and finish with a silent MP4.
                    audioBroken = true
                    Log.e("VideoExport", "bgm mux failed, exporting silent video", e)
                }
            }

            while (!eosReceived) {
                if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("Video export cancelled")

                if (frame < frameCount && !eosSent) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val queued = run {
                            val image = codec.getInputImage(inIndex)
                            if (image != null) {
                                renderer.render(track, frame, frameCount, canvas, background, timeline)
                                copyBitmapToImage(bitmap, image, yRow, uvRow)
                                true
                            } else {
                                val inBuf = codec.getInputBuffer(inIndex)
                                if (inBuf != null) {
                                    renderer.render(track, frame, frameCount, canvas, background, timeline)
                                    inBuf.clear()
                                    copyBitmapToNv12(bitmap, inBuf, yRow)
                                    true
                                } else false
                            }
                        }
                        if (queued) {
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
                        // Both tracks must be added before start(); audio is fully
                        // encoded by now, so its format is available.
                        if (bgm != null) {
                            audioTrack = runCatching { mux.addTrack(bgm.format) }
                                .onFailure { Log.e("VideoExport", "bgm track add failed, silent video", it) }
                                .getOrDefault(-1)
                            if (audioTrack < 0) audioBroken = true
                        }
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
                                // Interleave BGM by presentation time as the video advances.
                                writeAudioUpTo(info.presentationTimeUs)
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

            // No tail flush on purpose: the audio ends with the last video frame, so the muxer
            // never receives an audio sample past the end of the video.
            try {
                mux.stop()
            } catch (e: Exception) {
                Log.e("VideoExport", "mux stop failed", e)
                // Only the optional audio path may degrade the clip to a silent video; a
                // container that cannot be finalised is a real failure.
                if (!audioBroken) throw e
            }
            success = dest.length() > 0L
            Log.i(
                "MapSnapshot",
                "encode done in ${android.os.SystemClock.elapsedRealtime() - encodeStart} ms frames=$frameCount ${limits.width}x${limits.height}@${limits.fps}"
            )
            dest
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Must propagate: swallowing it would keep the cancelled job alive and let
            // "Cancel" look ignored.
            throw e
        } catch (e: Exception) {
            Log.e("VideoExport", "export failed", e)
            null
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { background?.recycle() } catch (_: Exception) {}
            if (!success) outFile?.delete()
        }
    }

    /** One AAC sample ready for the muxer. */
    private class AacSample(val data: ByteArray, val ptsUs: Long)

    private class EncodedAudio(val format: MediaFormat, val samples: List<AacSample>)

    /**
     * Encodes the bundled royalty-free loop (res/raw/bgm.wav, mono 22050 Hz) to AAC LC.
     * Runs fully in memory (~60 KB output); any failure returns null and the clip stays
     * silent instead of failing the whole export.
     */
    private fun encodeBgmAac(): EncodedAudio? = runCatching {
        val raw = context.resources.openRawResource(com.alexander.routestory.R.raw.bgm).use { it.readBytes() }
        if (raw.size <= 44) {
            Log.e("VideoExport", "BGM skipped: raw size ${raw.size} <= 44 bytes (no music)")
            return null
        }
        val sampleRate = 22_050
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        var outFormat: MediaFormat? = null
        val samples = ArrayList<AacSample>()
        val info = MediaCodec.BufferInfo()
        var offset = 44 // canonical PCM WAV header
        var samplesFed = 0L
        var eosSent = false
        var eosReceived = false
        while (!eosReceived) {
            if (!eosSent) {
                val idx = encoder.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val buf = encoder.getInputBuffer(idx)!!
                    buf.clear()
                    val size = minOf(8192, raw.size - offset)
                    if (size <= 0) {
                        encoder.queueInputBuffer(idx, 0, 0, ptsOf(samplesFed, sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    } else {
                        buf.put(raw, offset, size)
                        encoder.queueInputBuffer(idx, 0, size, ptsOf(samplesFed, sampleRate), 0)
                        samplesFed += size / 2
                        offset += size
                    }
                }
            }
            var out = encoder.dequeueOutputBuffer(info, 10_000L)
            while (out >= 0 || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = encoder.outputFormat
                } else if (out >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val ob = encoder.getOutputBuffer(out)!!
                        val data = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(data)
                        samples.add(AacSample(data, info.presentationTimeUs))
                    }
                    encoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eosReceived = true
                        break
                    }
                } else {
                    break
                }
                out = encoder.dequeueOutputBuffer(info, 0L)
            }
        }
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        EncodedAudio(outFormat ?: error("audio format not reported"), samples)
    }.onFailure { Log.e("VideoExport", "BGM encode failed", it) }.getOrNull()

    private fun ptsOf(sampleIndex: Long, sampleRate: Int): Long = sampleIndex * 1_000_000L / sampleRate

    /**
     * RGBA bitmap -> NV12 (semi-planar YUV420) straight into the codec input byte buffer.
     * BT.601 limited range. This avoids Image-plane layout quirks (planar vs semi-planar,
     * padded row strides) that produced grey/green frames on some encoders.
     */
    private fun copyBitmapToNv12(bitmap: Bitmap, out: java.nio.ByteBuffer, yRow: IntArray) {
        val w = bitmap.width
        val h = bitmap.height
        val uvStart = w * h
        for (row in 0 until h) {
            bitmap.getPixels(yRow, 0, w, 0, row, w, 1)
            var i = 0
            while (i < w) {
                val px = yRow[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                // Y = (16829*R + 33039*G + 6416*B) / 65536 + 16 (BT.601 limited range)
                val yVal = ((16829 * r + 33039 * g + 6416 * b) shr 16) + 16
                out.put(row * w + i, yVal.coerceIn(0, 255).toByte())
                i++
            }
            if (row % 2 == 0) {
                var j = 0
                while (j < w / 2) {
                    // Sample the even-X pixel of the 2x2 block for U/V.
                    val px = yRow[j * 2]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    // U = (-9714*R - 19071*G + 28784*B) / 65536 + 128
                    val uVal = ((-9714 * r - 19071 * g + 28784 * b) shr 16) + 128
                    // V = (28784*R - 24103*G - 4681*B) / 65536 + 128
                    val vVal = ((28784 * r - 24103 * g - 4681 * b) shr 16) + 128
                    val uvIdx = uvStart + (row / 2) * w + j * 2
                    out.put(uvIdx, uVal.coerceIn(0, 255).toByte())
                    out.put(uvIdx + 1, vVal.coerceIn(0, 255).toByte())
                    j++
                }
            }
        }
    }

    private fun copyBitmapToImage(bitmap: Bitmap, image: Image, yRow: IntArray, uvRow: IntArray) {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yPS = yPlane.pixelStride
        val yRS = yPlane.rowStride
        val uPS = uPlane.pixelStride
        val uRS = uPlane.rowStride
        val vPS = vPlane.pixelStride
        val vRS = vPlane.rowStride
        val halfW = w / 2
        for (row in 0 until h) {
            bitmap.getPixels(yRow, 0, w, 0, row, w, 1)
            var pos = row * yRS
            var i = 0
            while (i < w) {
                val px = yRow[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                // Y = (16829*R + 33039*G + 6416*B) / 65536 + 16 (BT.601 limited range)
                val yVal = ((16829 * r + 33039 * g + 6416 * b) shr 16) + 16
                yBuf.put(pos, yVal.coerceIn(0, 255).toByte())
                pos += yPS
                i++
            }
            if (row % 2 == 0) {
                val uvRowIdx = row / 2
                var j = 0
                while (j < halfW) {
                    // Sample the even-X pixel of the 2x2 block for U/V.
                    val px = yRow[j * 2]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    // U = (-9714*R - 19071*G + 28784*B) / 65536 + 128
                    val uVal = ((-9714 * r - 19071 * g + 28784 * b) shr 16) + 128
                    // V = (28784*R - 24103*G - 4681*B) / 65536 + 128
                    val vVal = ((28784 * r - 24103 * g - 4681 * b) shr 16) + 128
                    uBuf.put(uvRowIdx * uRS + j * uPS, uVal.coerceIn(0, 255).toByte())
                    vBuf.put(uvRowIdx * vRS + j * vPS, vVal.coerceIn(0, 255).toByte())
                    j++
                }
            }
        }
    }

    /**
     * Keeps only the newest temporary exports so storage does not grow indefinitely.
     * Archived per-ride copies (track_<id>.mp4) are owned by the UI and must survive.
     */
    private fun pruneOldVideos(dir: File, keep: Int = 4) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.filterNot { ARCHIVE_VIDEO_NAME.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(keep).forEach { it.delete() }
    }
}

/** The watchable per-ride copy written by the detail screen ("track_7.mp4"). */
private val ARCHIVE_VIDEO_NAME = Regex("track_\\d+\\.mp4")
