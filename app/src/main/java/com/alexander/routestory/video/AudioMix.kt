package com.alexander.routestory.video

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin audio maths for the export: resampling, mixing, a RIFF container for the
 * debug dump and the metrics used to tell music from noise.
 *
 * No Android imports on purpose: this is the noise-sensitive half of the audio pipeline
 * (resample -> mix -> PCM bytes), so all of it is covered by JVM unit tests.
 */
object AudioMix {

    /** Full scale of a signed 16-bit sample. */
    const val FULL_SCALE = 32_767

    /**
     * Zero crossings per second above which a stream is treated as noise-like.
     * A soft bed (content up to a few kHz) sits far below it, white noise sits far above.
     */
    const val NOISE_CROSSING_RATIO = 0.22

    /** A cue overlaid on the bed, anchored to an absolute video frame. */
    class Cue(val frame: Int, val samples: IntArray, val gain: Float)

    /** Peak / RMS / zero crossings / duration of a 16-bit mono stream. */
    class Stats(
        val peak: Int,
        val rms: Double,
        val crossingsPerSecond: Double,
        val durationMs: Long
    ) {
        /** True when the stream looks like broadband noise instead of music. */
        fun isNoiseLike(sampleRate: Int): Boolean =
            sampleRate > 0 && crossingsPerSecond > sampleRate * NOISE_CROSSING_RATIO
    }

    /**
     * Streaming meter: the on-device check decodes the exported MP4 in blocks, the unit
     * tests feed whole arrays, and both share this single definition of "noise-like".
     */
    class Meter(private val sampleRate: Int) {
        private var count = 0L
        private var peak = 0
        private var sumSquares = 0.0
        private var crossings = 0L
        private var previous = 0

        fun add(value: Int) {
            if (count > 0L && (value >= 0) != (previous >= 0)) crossings++
            previous = value
            peak = max(peak, abs(value))
            sumSquares += value.toDouble() * value
            count++
        }

        fun stats(): Stats = Stats(
            peak = peak,
            rms = if (count == 0L) 0.0 else sqrt(sumSquares / count),
            crossingsPerSecond = if (count == 0L || sampleRate <= 0) 0.0
            else crossings.toDouble() * sampleRate / count,
            durationMs = if (sampleRate <= 0) 0L else count * 1000L / sampleRate
        )
    }

    /**
     * Linear-interpolation resample of 16-bit mono [samples] from [fromRate] to [toRate].
     *
     * The export always encodes at 44.1 kHz: every AAC encoder supports it, while
     * 22.05 kHz mono is a rate where SoC encoders produced garbage on some devices.
     * The bed is band-limited far below the source Nyquist rate, so a plain linear
     * interpolation keeps it intact.
     */
    fun resample(samples: IntArray, fromRate: Int, toRate: Int): IntArray {
        if (fromRate == toRate || fromRate <= 0 || toRate <= 0 || samples.isEmpty()) return samples
        val out = IntArray((samples.size.toLong() * toRate / fromRate).toInt())
        if (out.isEmpty()) return out
        val step = fromRate.toDouble() / toRate
        for (i in out.indices) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, samples.size - 1)
            val i1 = min(i0 + 1, samples.size - 1)
            val frac = pos - i0
            out[i] = (samples[i0] * (1.0 - frac) + samples[i1] * frac).roundToInt()
        }
        return out
    }

    /**
     * Builds the exact PCM stream the AAC encoder is fed: [bgm] tiled for the whole clip
     * plus [cues] overlaid at their frame positions.
     *
     * Instead of hard clipping at full scale (which crackled on the peaks) the finished
     * mix is scaled down once when the sum overflows, and its length is exactly
     * [totalSamples], so the stream always covers the whole video and never needs the
     * fragile "shift a whole loop copy forward" trick at the muxer.
     *
     * Returns little-endian 16-bit mono bytes of [totalSamples] * 2 bytes.
     */
    fun mix(
        bgm: IntArray,
        bgmGain: Float,
        cues: List<Cue>,
        frameRate: Int,
        sampleRate: Int,
        totalSamples: Int
    ): ByteArray {
        require(bgm.isNotEmpty()) { "bgm is empty" }
        require(frameRate > 0 && sampleRate > 0) { "invalid rates" }
        require(totalSamples > 0) { "totalSamples must be positive" }
        val acc = IntArray(totalSamples)
        for (i in 0 until totalSamples) acc[i] = (bgm[i % bgm.size] * bgmGain).roundToInt()
        for (cue in cues) {
            if (cue.samples.isEmpty()) continue
            // Long maths: frame * sampleRate overflows Int on long clips.
            val start = (cue.frame.toLong() * sampleRate / frameRate).toInt()
            for (j in cue.samples.indices) {
                val idx = start + j
                if (idx < 0 || idx >= totalSamples) continue
                acc[idx] += (cue.samples[j] * cue.gain).roundToInt()
            }
        }
        var peak = 0
        for (v in acc) peak = max(peak, abs(v))
        val scale = if (peak > FULL_SCALE) FULL_SCALE.toDouble() / peak else 1.0
        val out = ByteArray(totalSamples * 2)
        for (i in 0 until totalSamples) {
            val v = (acc[i] * scale).roundToInt().coerceIn(-FULL_SCALE - 1, FULL_SCALE)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Metrics of little-endian 16-bit mono [pcm]. */
    fun measure(pcm: ByteArray, sampleRate: Int): Stats {
        val meter = Meter(sampleRate)
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() and 0xFF
            meter.add(((hi shl 8) or lo).toShort().toInt())
            i += 2
        }
        return meter.stats()
    }

    /** Metrics of already decoded samples. */
    fun measure(samples: IntArray, sampleRate: Int): Stats {
        val meter = Meter(sampleRate)
        for (s in samples) meter.add(s)
        return meter.stats()
    }

    /**
     * Wraps raw 16-bit mono [pcm] into a canonical RIFF/WAVE container: used for the
     * DEBUG dump, so the exact stream fed to the encoder can be listened to on the device
     * (and re-parsed by [WavPcmDecoder] in a unit test).
     */
    fun wavBytes(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)

        fun tag(offset: Int, value: String) {
            for (i in value.indices) out[offset + i] = value[i].code.toByte()
        }

        fun le32(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        fun le16(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        tag(0, "RIFF")
        le32(4, 36 + pcm.size)
        tag(8, "WAVE")
        tag(12, "fmt ")
        le32(16, 16)
        le16(20, 1)              // PCM
        le16(22, 1)              // mono
        le32(24, sampleRate)
        le32(28, sampleRate * 2) // byte rate
        le16(32, 2)              // block align
        le16(34, 16)             // bits per sample
        tag(36, "data")
        le32(40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }
}
