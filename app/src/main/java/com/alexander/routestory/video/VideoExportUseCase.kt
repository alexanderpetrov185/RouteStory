package com.alexander.routestory.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.alexander.routestory.BuildConfig
import com.alexander.routestory.R
import com.alexander.routestory.domain.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Encodes an animated route video fully on-device:
 * Canvas frames -> RGBA to YUV420 -> MediaCodec (H.264) -> MediaMuxer (MP4).
 * A light royalty-free background loop (res/raw/bgm.wav) is mixed with the SFX cues
 * (res/raw/whoosh.wav at the speed peak, res/raw/shutter.wav at each photo insert)
 * and AAC-encoded as a single mono track interleaved into the same MP4; any audio
 * failure keeps the silent video.
 */
@Singleton
class VideoExportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotProvider: MapSnapshotProvider
) {

    suspend fun export(
        track: Track,
        limits: VideoLimits,
        bgmPath: String? = null,
        keepSfx: Boolean = true,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.Default) {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surfaceEncoderLocal: EncoderSurface? = null
        var outFile: File? = null
        var success = false
        var background: Bitmap? = null
        var closeUpBackground: Bitmap? = null
        var followSegments: List<MapSnapshotProvider.FollowSegment> = emptyList()
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
            // 2x supersampled backdrops: the renderer zooms the bitmap up to ~4x during
            // the zoom/follow phases, so a frame-resolution bitmap went visibly pixelated.
            // Capped so memory stays bounded (Pro 720p keeps the 1x backdrop).
            val oversample = if (limits.width * 2 <= MAX_BACKDROP_WIDTH) 2 else 1
            background = withTimeoutOrNull(12_000L) {
                runCatching {
                    snapshotProvider.snapshot(
                        track, limits.width * oversample, limits.height * oversample
                    )
                }
                    .onFailure { Log.e("VideoExport", "snapshot failed", it) }
                    .getOrNull()
            }
            // Sprint 34: second, crisper backdrop for the follow/zoom phase.
            // Failure is non-fatal: the renderer falls back to overview-only.
            // Sprint 40: the network is used HERE (before the encode loop), never while
            // frames are being encoded: prefetch the follow tile grid, then stitch the
            // segments from the disk cache only. A missing tile becomes a grey cell.
            // Network is allowed only here. The strict report gates encoding: even one
            // missing tile (or an incomplete segment) fails before MediaCodec is created.
            val followReport = snapshotProvider.prefetchFollowTiles(
                track, limits.width * oversample, limits.height * oversample
            )
            Log.i(
                "VideoExport",
                "follow prefetch ready=${followReport.readyTiles}/${followReport.expectedTiles} " +
                    "segments=${followReport.crops.size} missing=${followReport.missingTiles.size}"
            )
            if (!followReport.isComplete) {
                throw IllegalStateException(
                    "follow tiles incomplete: ${followReport.readyTiles}/${followReport.expectedTiles}"
                )
            }
            followSegments = snapshotProvider.followSnapshots(followReport.crops)
            if (followSegments.size != followReport.crops.size || followSegments.any { it.bitmap == null }) {
                followSegments.forEach { it.bitmap?.recycle() }
                followSegments = emptyList()
                throw IllegalStateException("follow segments incomplete")
            }
            closeUpBackground = followSegments.first().bitmap
            val snapshotMs = android.os.SystemClock.elapsedRealtime() - snapshotStart
            Log.i(
                "VideoExport",
                "snapshot done in $snapshotMs ms " +
                    "background=${background != null} closeUp=${closeUpBackground != null}"
            )
            // Cancel must win even before the first frame: snapshot + audio mix run for
            // seconds with no other suspension point.
            currentCoroutineContext().ensureActive()
            if (background == null && closeUpBackground == null) {
                Log.e("VideoExport", "snapshot failed: no background bitmaps produced")
            }
            val encodeStart = android.os.SystemClock.elapsedRealtime()
            // Sprint 27: the BGM track is optional. Every audio step below is guarded, so a
            // failing encode or mux only leaves the MP4 silent — the export stays successful.
            // Sprint 35: SFX cues (wind at the speed peak, shutter at each photo insert)
            // are mixed into the BGM PCM stream BEFORE the single AAC encode, so the video
            // carries one mono audio track and a missing SFX file never fails the export.
            val timeline = RouteVideoTimeline(track, limits.fps, limits.durationSec, limits.maxPhotos)
            val bgm: EncodedAudio? = encodeMixedAudio(timeline, bgmPath, keepSfx)
            Log.i("VideoExport", "bgm samples=${bgm?.samples?.size ?: 0}")
            currentCoroutineContext().ensureActive()
            val renderer = RouteFrameRenderer(limits)
            // The timeline owns frame counting: map animation plus a pause for every photo.
            val frameCount = timeline.totalFrames
            Log.i("VideoExport",
                "timeline totalFrames=$frameCount " +
                "${limits.width}x${limits.height}@${limits.fps}fps " +
                "photos=${timeline.photoCount}")
            // One reusable frame bitmap: no per-frame allocations on weak devices.
            val bitmap = Bitmap.createBitmap(limits.width, limits.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Full-frame pixel scratch: ONE getPixels call per frame instead of one per
            // row (a JNI call each — hundreds of them on a frame-resolution bitmap).
            val pixels = IntArray(limits.width * limits.height)
            // Row-plane scratch reused for every frame (biggest allocation in the loop).
            val yRow = IntArray(limits.width)
            val uvRow = IntArray(limits.width / 2)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, limits.width, limits.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, limits.fps)
                setInteger(MediaFormat.KEY_BIT_RATE, limits.bitrate)
                // Every-second keyframes were the old setting; 2 s cuts encoder load and
                // file size with no visible effect on the mostly-static footage.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            // Sprint 37: prefer the surface input - the frame bitmap is uploaded as a GL
            // texture and drawn straight into the hardware encoder surface, removing the
            // slow per-pixel RGB->YUV Kotlin conversion (the main encode bottleneck).
            // The buffer path (Image planes / NV12) stays as a fallback when EGL/GL is
            // unavailable; the Image path keeps the Sprint 15 colour fix.
            var codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            var usingSurface = false
            var surfaceEncoder: EncoderSurface? = null
            try {
                surfaceEncoder = EncoderSurface(codec.createInputSurface())
                surfaceEncoder.prepare()
                codec.start()
                usingSurface = true
                surfaceEncoderLocal = surfaceEncoder
            } catch (t: Throwable) {
                Log.w("VideoExport", "surface encoder unavailable, falling back to YUV buffers", t)
                runCatching { surfaceEncoder?.release() }
                surfaceEncoder = null
                surfaceEncoderLocal = null
                runCatching { codec.stop() }
                runCatching { codec.release() }
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder = codec
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
            // Colour correctness (Sprint 15): prefer the codec-provided Image planes —
            // the encoder itself declares the plane layout, which avoids the dark-green
            // cast of hand-rolled NV12 on encoders that expect padded/planar input.
            // The NV12 byte-buffer path stays as fallback when getInputImage returns null.
            val mux = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var videoTrack = -1
            var audioTrack = -1
            var muxerStarted = false
            var eosSent = false
            var eosReceived = false
            var frame = 0
            val info = MediaCodec.BufferInfo()
            // Position of the next audio sample to interleave (the mix already spans the
            // whole clip, see AudioMix.mix).
            var audioNext = 0
            var audioEnded = false
            // Set when the muxer rejects an audio sample: the video keeps encoding and the clip
            // simply stays silent instead of failing the whole export.
            var audioBroken = false
            var lastAudioPtsUs = -1L
            fun writeAudioUpTo(ptsUs: Long) {
                if (bgm == null || audioTrack < 0 || audioBroken) return
                val samples = bgm.samples
                if (samples.isEmpty()) return
                // Audio never runs past the video timeline: samples with a pts beyond the
                // current video sample are what used to make the muxer throw.
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
                    // Plain monotonic cursor: the mix was built for exactly the clip length,
                    // so every encoded sample is written once at its own pts. The old
                    // whole-loop shift (period = the last sample's pts, one AAC frame short
                    // of the real loop length) drifted and re-wrote samples at every seam.
                    while (audioNext < samples.size && samples[audioNext].ptsUs <= ptsUs) {
                        val s = samples[audioNext]
                        write(s.data, s.ptsUs)
                        audioNext++
                    }
                    if (audioNext >= samples.size && !audioEnded) {
                        audioEnded = true
                        Log.i(
                            "VideoExport",
                            "audio cursor reached the end at ${samples.last().ptsUs}us"
                        )
                    }
                } catch (e: Exception) {
                    // Optional track: log and finish with a silent MP4.
                    audioBroken = true
                    Log.e("VideoExport", "audio mux failed, exporting silent video", e)
                }
            }

            while (!eosReceived) {
                if (!currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("Video export cancelled")

                if (frame < frameCount && !eosSent) {
                    val ptsUs = frame * 1_000_000L / limits.fps
                    val isLast = frame == frameCount - 1
                    // Static middle of a photo pause: the picture is identical to the
                    // previous frame — skip rendering (and the GL re-upload) and repeat
                    // the cached frame with a new presentation time. Photo pauses are
                    // up to ~25% of all frames.
                    val repeatPicture = frame > 0 && timeline.isStaticPhotoFrame(frame)
                    if (usingSurface) {
                        val se = surfaceEncoder!!
                        if (repeatPicture) {
                            se.repeatFrame(ptsUs)
                        } else {
                            renderer.render(track, frame, frameCount, canvas, background, followSegments, timeline)
                            se.drawBitmap(bitmap, ptsUs)
                        }
                        frame++
                        // Report progress only while still active: after cancel() the
                        // holder drops the job, and this call must not flip the state
                        // back to Running (the flicker defect).
                        currentCoroutineContext().ensureActive()
                        onProgress(frame.toFloat() / frameCount)
                        if (isLast) {
                            codec.signalEndOfInputStream()
                            eosSent = true
                        }
                    } else {
                        val inIndex = codec.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val queued = run {
                                val image = codec.getInputImage(inIndex)
                                if (image != null) {
                                    if (!repeatPicture) {
                                        renderer.render(track, frame, frameCount, canvas, background, followSegments, timeline)
                                    }
                                    copyBitmapToImage(bitmap, image, pixels, yRow, uvRow)
                                    true
                                } else {
                                    val inBuf = codec.getInputBuffer(inIndex)
                                    if (inBuf != null) {
                                        if (!repeatPicture) {
                                            renderer.render(track, frame, frameCount, canvas, background, followSegments, timeline)
                                        }
                                        inBuf.clear()
                                        copyBitmapToNv12(bitmap, inBuf, pixels, yRow)
                                        true
                                    } else false
                                }
                            }
                            if (queued) {
                                val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                codec.queueInputBuffer(inIndex, 0, limits.width * limits.height * 3 / 2, ptsUs, flags)
                                if (isLast) eosSent = true
                                frame++
                                // Report progress only while still active: after cancel() the
                                // holder drops the job, and this call must not flip the state
                                // back to Running (the flicker defect).
                                currentCoroutineContext().ensureActive()
                                onProgress(frame.toFloat() / frameCount)
                            }
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
                        // Late cancel check right before the sample loop takes over.
                        currentCoroutineContext().ensureActive()
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
            if (BuildConfig.DEBUG && success) logAudioCheck(dest)
            val encodeMs = android.os.SystemClock.elapsedRealtime() - encodeStart
            Log.i(
                "MapSnapshot",
                "encode done in $encodeMs ms frames=$frameCount ${limits.width}x${limits.height}@${limits.fps} " +
                    "avg=${if (frameCount > 0) encodeMs / frameCount else 0} ms/frame"
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
            try { surfaceEncoderLocal?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { background?.recycle() } catch (_: Exception) {}
            for (seg in followSegments) { try { seg.bitmap?.recycle() } catch (_: Exception) {} }
            if (!success) outFile?.delete()
        }
    }

    /** One AAC sample ready for the muxer. */
    private class AacSample(val data: ByteArray, val ptsUs: Long)

    private class EncodedAudio(val format: MediaFormat, val samples: List<AacSample>)

    /**
     * Encodes the bundled audio bed (res/raw/bgm.wav, see tools/make_bgm.py) plus, once
     * [SFX_ENABLED] is on, the wind/shutter cues into a single mono AAC track.
     *
     * Every step here is deliberately strict, because one wrong byte anywhere in this path
     * used to turn the whole clip into white noise:
     *  - [WavPcmDecoder] walks the real RIFF chunks (no blind 44-byte skip);
     *  - the bed is resampled to 44.1 kHz, the rate every AAC encoder supports;
     *  - the AOSP software encoder is preferred over SoC hardware encoders;
     *  - SBR is disabled, so the effective rate cannot change under our feet;
     *  - the input chunk is sized by the codec buffer's real capacity;
     *  - the finished mix is measured (peak/RMS/zero crossings) and logged, so a
     *    noise-like bed is visible in logcat instead of only in the user's ears.
     */
    private fun encodeMixedAudio(
        timeline: RouteVideoTimeline,
        bgmPath: String? = null,
        keepSfx: Boolean = true
    ): EncodedAudio? = runCatching {
        // 1) Bed source: a user-picked track (Sprint 42, decoded via MediaExtractor/
        //    MediaCodec - mp3/aac/flac/m4a from the device) or the bundled bgm.wav.
        //    ANY custom-decode failure falls back to the bundled bed: the export must
        //    never fail because of a music file.
        val totalSamples =
            (timeline.totalFrames.toLong() * AUDIO_SAMPLE_RATE / timeline.frameRate).toInt()
        val custom = bgmPath?.let { path ->
            val maxSeconds = totalSamples / AUDIO_SAMPLE_RATE + 5
            runCatching { decodeMusicToPcm(path, maxSeconds) }
                .onFailure { Log.w("VideoExport", "custom BGM decode failed, fallback bgm.wav", it) }
                .getOrNull()
        }
        val bed: IntArray
        val srcRate: Int
        if (custom != null) {
            bed = AudioMix.resample(custom.first, custom.second, AUDIO_SAMPLE_RATE)
            srcRate = custom.second
            Log.i(
                "VideoExport",
                "$AUDIO_PIPELINE_TAG custom bgm ${custom.first.size}@${srcRate}Hz -> ${bed.size}@$AUDIO_SAMPLE_RATE}Hz"
            )
        } else {
            // Strict RIFF parser (real "data" offset/size) for the bundled bed.
            val bgm = openRaw(R.raw.bgm)?.let { WavPcmDecoder.parse(it) }
            if (bgm == null || bgm.samples.isEmpty()) {
                Log.e("VideoExport", "BGM skipped: unusable PCM (no music)")
                return null
            }
            bed = AudioMix.resample(bgm.samples, bgm.sampleRate, AUDIO_SAMPLE_RATE)
            srcRate = bgm.sampleRate
            Log.i(
                "VideoExport",
                "$AUDIO_PIPELINE_TAG bgm=${bgm.samples.size}@${bgm.sampleRate}Hz " +
                    "ch=${bgm.channels} bits=${bgm.bitsPerSample} header=${bgm.hasHeader} " +
                    "-> ${bed.size}@${AUDIO_SAMPLE_RATE}Hz"
            )
        }

        // 2) Cues in absolute video-frame coordinates: wind at the speed peak, shutter at
        //    every photo insert. Gated by SFX_ENABLED (build-time) AND keepSfx (user pref).
        val cues = ArrayList<AudioMix.Cue>()
        if (SFX_ENABLED && keepSfx) {
            val whoosh = readSfxCue(R.raw.whoosh)
            if (whoosh != null && timeline.peakFrame >= 0) {
                cues.add(AudioMix.Cue(timeline.peakFrame, whoosh, WHOOSH_GAIN))
            }
            for (i in 0 until timeline.photoCount) {
                val shutter = readSfxCue(R.raw.shutter) ?: break
                val start = timeline.photoStartFrame(i)
                if (start >= 0) cues.add(AudioMix.Cue(start, shutter, SHUTTER_GAIN))
            }
        } else {
            Log.i("VideoExport", "$AUDIO_PIPELINE_TAG SFX muted (keepSfx=$keepSfx)")
        }

        // 3) Mix for exactly the clip length: the bed is tiled across every frame of the
        //    clip and the cues land on their own sample positions, with headroom applied
        //    once instead of hard clipping on the peaks. (totalSamples was computed in
        //    step 1 for the custom-BGM decode budget.)
        val pcm = AudioMix.mix(
            bed, BGM_GAIN, cues, timeline.frameRate, AUDIO_SAMPLE_RATE, totalSamples
        )
        val stats = AudioMix.measure(pcm, AUDIO_SAMPLE_RATE)
        val verdict = if (stats.isNoiseLike(AUDIO_SAMPLE_RATE)) "NOISE-LIKE!" else "music-like"
        Log.i(
            "VideoExport",
            "$AUDIO_PIPELINE_TAG cues=${cues.size} samples=$totalSamples dur=${stats.durationMs}ms " +
                "peak=${stats.peak} rms=${stats.rms.roundToInt()} " +
                "crossPerSec=${stats.crossingsPerSecond.roundToInt()} $verdict"
        )
        dumpMixForDebugging(pcm)

        // 4) Encode the mixed mono PCM as a single AAC track (muxed with the video).
        encodeAac(pcm)
    }.onFailure { Log.e("VideoExport", "audio encode failed, exporting silent video", it) }.getOrNull()

    /** Reads a res/raw resource as bytes; null when it cannot be opened. */
    private fun openRaw(resId: Int): ByteArray? = runCatching {
        context.resources.openRawResource(resId).use { it.readBytes() }
    }.onFailure { Log.w("VideoExport", "raw resource $resId unreadable", it) }.getOrNull()

    /**
     * Sprint 42: decodes a user-picked music file (mp3/aac/flac/m4a - whatever the
     * device decoders support) to mono 16-bit PCM via MediaExtractor + MediaCodec.
     * Output is capped at [maxSeconds] so a full album cannot blow up memory; null
     * on ANY failure (caller falls back to the bundled bed).
     */
    private fun decodeMusicToPcm(path: String, maxSeconds: Int): Pair<IntArray, Int>? {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return runCatching {
            extractor = MediaExtractor()
            extractor!!.setDataSource(file.absolutePath)
            var format: MediaFormat? = null
            var mime: String? = null
            for (i in 0 until extractor!!.trackCount) {
                val f = extractor!!.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    extractor!!.selectTrack(i)
                    format = f
                    mime = m
                    break
                }
            }
            val fmt = format
            if (fmt == null) {
                extractor!!.release()
                return null
            }
            val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime!!)
            codec!!.configure(fmt, null, null, 0)
            codec!!.start()
            val limit = maxSeconds * sampleRate // mono frames (first channel)
            val out = IntArray(limit)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && written < limit) {
                if (!inputDone) {
                    val inIdx = codec!!.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec!!.getInputBuffer(inIdx)!!
                        val read = extractor!!.readSampleData(buf, 0)
                        if (read < 0) {
                            codec!!.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec!!.queueInputBuffer(inIdx, 0, read, extractor!!.sampleTime, 0)
                            extractor!!.advance()
                        }
                    }
                }
                val outIdx = codec!!.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val obuf = codec!!.getOutputBuffer(outIdx)!!
                        // 16-bit little-endian PCM frames; take the FIRST channel (mono bed).
                        val bytesPerFrame = 2 * channels
                        val frames = minOf(info.size / bytesPerFrame, limit - written)
                        for (f in 0 until frames) {
                            val p = info.offset + f * bytesPerFrame
                            val lo = obuf.get(p).toInt() and 0xFF
                            val hi = obuf.get(p + 1).toInt()
                            out[written + f] = (hi shl 8) or lo
                        }
                        written += frames
                    }
                    codec!!.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec!!.stop()
            codec!!.release()
            codec = null
            extractor!!.release()
            extractor = null
            if (written == 0) null else out.copyOf(written) to sampleRate
        }.onFailure {
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            Log.w("VideoExport", "decodeMusicToPcm failed for $path", it)
        }.getOrNull()
    }

    /**
     * Decodes a SFX cue through [WavPcmDecoder] and resamples it to [AUDIO_SAMPLE_RATE].
     * Null when the cue is unusable: a missing cue stays silent instead of failing the export.
     */
    private fun readSfxCue(resId: Int): IntArray? {
        val wav = openRaw(resId)?.let { WavPcmDecoder.parse(it) } ?: return null
        if (wav.samples.isEmpty()) return null
        val cue = AudioMix.resample(wav.samples, wav.sampleRate, AUDIO_SAMPLE_RATE)
        // A cue that decodes into broadband noise (a misaligned grid, a corrupted resource)
        // would hiss over the bed: drop that cue alone and keep the music.
        val stats = AudioMix.measure(cue, AUDIO_SAMPLE_RATE)
        if (stats.isNoiseLike(AUDIO_SAMPLE_RATE)) {
            Log.w(
                "VideoExport",
                "$AUDIO_PIPELINE_TAG SFX cue $resId noise-like " +
                    "(crossPerSec=${stats.crossingsPerSecond.roundToInt()}) - skipped, bed keeps playing"
            )
            return null
        }
        return cue
    }

    /** Encodes the mixed PCM into one mono AAC track, ready to be interleaved. */
    private fun encodeAac(pcm: ByteArray): EncodedAudio {
        val codec = createAudioEncoder()
        Log.i("VideoExport", "$AUDIO_PIPELINE_TAG codec=${codec.name} rate=$AUDIO_SAMPLE_RATE mono")
        val format = MediaFormat
            .createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1)
            .apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                // 96 kbps mono at 44.1 kHz: transparent for the bed and the cues, still small.
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                // 0 = no SBR: HE-AAC would double the effective output rate behind our back,
                // which desyncs the muxer's audio timestamps against the video.
                setInteger(MediaFormat.KEY_AAC_SBR_MODE, 0)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val encoded = try {
            encodeAacFromPcm(codec, pcm)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        val outFormat = encoded.format ?: error("audio format not reported")
        check(outFormat.getByteBuffer("csd-0") != null) { "audio format without codec config" }
        return EncodedAudio(outFormat, encoded.samples)
    }

    /**
     * Prefers the AOSP software AAC encoder. SoC hardware AAC encoders are the ones that
     * produced garbage (or no) audio on some devices; we encode a few seconds of mono once
     * per export, so the reference implementation is the safe choice.
     */
    private fun createAudioEncoder(): MediaCodec {
        val software = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && !info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) }
            }?.name
        }.getOrNull()
        return if (software != null) MediaCodec.createByCodecName(software)
        else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    }

    /** Feeds little-endian 16-bit mono [pcm] into a running AAC encoder; collects the frames. */
    private fun encodeAacFromPcm(encoder: MediaCodec, pcm: ByteArray): AacEncodeResult {
        val info = MediaCodec.BufferInfo()
        val samples = ArrayList<AacSample>()
        var outFormat: MediaFormat? = null
        var offset = 0
        var samplesFed = 0L
        var eosSent = false
        var eosReceived = false
        while (!eosReceived) {
            if (!eosSent) {
                val idx = encoder.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val buf = encoder.getInputBuffer(idx)
                    buf?.clear()
                    // Never assume a capacity: KEY_MAX_INPUT_SIZE is only a hint, and an
                    // overflowing put() used to kill the whole audio track silently.
                    val capacity = (buf?.capacity() ?: 0) and 1.inv()
                    val remaining = pcm.size - offset
                    val size = if (remaining <= 0 || capacity < 2) 0 else minOf(capacity, remaining)
                    when {
                        remaining <= 0 -> {
                            encoder.queueInputBuffer(
                                idx, 0, 0, ptsOf(samplesFed, AUDIO_SAMPLE_RATE),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosSent = true
                        }
                        // Unusable buffer: hand it back empty instead of treating it as EOS.
                        size <= 0 -> encoder.queueInputBuffer(
                            idx, 0, 0, ptsOf(samplesFed, AUDIO_SAMPLE_RATE), 0
                        )
                        else -> {
                            buf!!.put(pcm, offset, size)
                            encoder.queueInputBuffer(
                                idx, 0, size, ptsOf(samplesFed, AUDIO_SAMPLE_RATE), 0
                            )
                            samplesFed += size / 2
                            offset += size
                        }
                    }
                }
            }
            var out = encoder.dequeueOutputBuffer(info, 10_000L)
            while (out >= 0 || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = encoder.outputFormat
                } else if (out >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
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
        return AacEncodeResult(outFormat, samples)
    }

    private data class AacEncodeResult(val format: MediaFormat?, val samples: List<AacSample>)

    private fun ptsOf(sampleIndex: Long, sampleRate: Int): Long = sampleIndex * 1_000_000L / sampleRate

    /**
     * RGBA bitmap -> NV12 (semi-planar YUV420) straight into the codec input byte buffer.
     * BT.601 limited range. This avoids Image-plane layout quirks (planar vs semi-planar,
     * padded row strides) that produced grey/green frames on some encoders.
     * [pixels] is a full-frame scratch array: one getPixels call per frame total.
     */
    private fun copyBitmapToNv12(bitmap: Bitmap, out: java.nio.ByteBuffer, pixels: IntArray, yRow: IntArray) {
        val w = bitmap.width
        val h = bitmap.height
        val uvStart = w * h
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (row in 0 until h) {
            val rowStart = row * w
            var i = 0
            while (i < w) {
                val px = pixels[rowStart + i]
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
                    val px = pixels[rowStart + j * 2]
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

    private fun copyBitmapToImage(bitmap: Bitmap, image: Image, pixels: IntArray, yRow: IntArray, uvRow: IntArray) {
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
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (row in 0 until h) {
            val rowStart = row * w
            var pos = row * yRS
            var i = 0
            while (i < w) {
                val px = pixels[rowStart + i]
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
                    val px = pixels[rowStart + j * 2]
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

    /**
     * DEBUG only: writes the exact PCM stream the encoder is fed to the app cache, so the
     * "noise or music" question can be answered by listening to it on the device
     * (audio_debug/mix_debug.wav), independently of the AAC/muxer/player stages.
     */
    private fun dumpMixForDebugging(pcm: ByteArray) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.cacheDir, "audio_debug").apply { mkdirs() }
            val out = File(dir, "mix_debug.wav")
            out.outputStream().use { it.write(AudioMix.wavBytes(pcm, AUDIO_SAMPLE_RATE)) }
            Log.i("VideoExport", "$AUDIO_PIPELINE_TAG dump ${pcm.size / 2} samples -> ${out.absolutePath}")
        }.onFailure { Log.w("VideoExport", "mix dump failed", it) }
    }

    /**
     * DEBUG only: decodes the audio track of the finished MP4 and says whether it carries
     * music or garbage. Distinguishes "our pipeline produced noise" from "the player shows
     * noise", which a log alone cannot tell.
     */
    private fun logAudioCheck(file: File) {
        val check = AudioCheck.measure(file)
        if (check == null) {
            Log.w("VideoExport", "$AUDIO_PIPELINE_TAG audioCheck: no decodable audio track")
            return
        }
        val verdict = if (check.noiseLike) "FAIL (noise-like)" else "OK (music-like)"
        Log.i(
            "VideoExport",
            "$AUDIO_PIPELINE_TAG audioCheck $verdict rate=${check.sampleRate} ch=${check.channels} " +
                "dur=${check.stats.durationMs}ms peak=${check.stats.peak} " +
                "rms=${check.stats.rms.roundToInt()} " +
                "crossPerSec=${check.stats.crossingsPerSecond.roundToInt()}"
        )
    }
}

/** The watchable per-ride copy written by the detail screen ("track_7.mp4"). */
private val ARCHIVE_VIDEO_NAME = Regex("track_\\d+\\.mp4")

/** Whoosh/shutter mix gains (see encodeMixedAudio): loud enough over the bed, no clipping. */
private const val WHOOSH_GAIN = 0.6f
private const val SHUTTER_GAIN = 0.8f

/** Bed gain: leaves headroom for the cues instead of clipping (see AudioMix.mix). */
private const val BGM_GAIN = 0.7f

/** Log marker: proves on the device which audio pipeline is actually running. */
private const val AUDIO_PIPELINE_TAG = "audio pipeline v2"

/** Mono AAC output rate: every encoder supports 44.1 kHz (22.05 kHz did not). */
private const val AUDIO_SAMPLE_RATE = 44_100

/**
 * SFX switch: whoosh at the speed peak + shutter at every photo insert, mixed over the
 * verified bed. Cues are individually noise-checked (see readSfxCue), so a bad cue can
 * only silence itself, never the music.
 */
private const val SFX_ENABLED = true

/**
 * Cap for the 2x supersampled backdrop width: 736*2 = 1472 fits, Pro 720p (1280*2)
 * would blow up the bitmap memory (x2 backdrops, each with a brightened copy), so
 * Pro keeps the 1x backdrop (it already gains detail from close-up z17).
 */
private const val MAX_BACKDROP_WIDTH = 1600
