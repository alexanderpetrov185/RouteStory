package com.alexander.routestory.video

/**
 * Strict 16-bit PCM WAV reader.
 *
 * The previous decoder skipped a hard-coded 44 bytes whenever a RIFF signature was
 * present. That is only correct for a canonical header: an extra chunk ("LIST"/"fact"),
 * a different fmt size or non-16-bit data pushes the payload elsewhere, every sample is
 * then assembled from halves of two neighbours, and the whole track becomes white noise.
 * This parser walks the real chunk list and uses the actual "data" offset and size, so
 * the sample grid can never shift.
 *
 * Pure JVM (no Android imports) so the noise-sensitive parsing is covered by
 * [WavPcmDecoderTest] — including a test against the real res/raw/bgm.wav.
 */
object WavPcmDecoder {

    /** Rate assumed for a headerless raw PCM payload (matches tools/make_bgm.py). */
    private const val FALLBACK_RATE = 22_050

    /** Decoded payload; [hasHeader] is false for a bare PCM buffer. */
    class WavPcm(
        val samples: IntArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val hasHeader: Boolean
    )

    /** True when [raw] starts with a RIFF/WAVE signature. */
    fun hasWavHeader(raw: ByteArray): Boolean =
        raw.size >= 12 &&
            tag(raw, 0) == "RIFF" &&
            tag(raw, 8) == "WAVE"

    /** Samples only, empty when the payload is unusable. */
    fun decode(raw: ByteArray, skipHeader: Boolean = true): IntArray =
        parse(raw, assumeBarePcm = !skipHeader)?.samples ?: IntArray(0)

    /** Parses the RIFF chunk map (or bare PCM when there is no signature at all). */
    fun parse(raw: ByteArray): WavPcm? = parse(raw, assumeBarePcm = false)

    /**
     * [assumeBarePcm] = true takes the whole buffer as 16-bit mono little-endian PCM at
     * [FALLBACK_RATE] and never looks for a header (the old `skipHeader = false` contract).
     */
    fun parse(raw: ByteArray, assumeBarePcm: Boolean): WavPcm? {
        if (raw.size < 2) return null
        if (assumeBarePcm || !hasWavHeader(raw)) return barePcm(raw)
        var offset = 12
        var channels = 1
        var sampleRate = FALLBACK_RATE
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= raw.size) {
            val id = tag(raw, offset)
            val size = le32(raw, offset + 4)
            val body = offset + 8
            if (size < 0 || body.toLong() + size > raw.size) break // truncated chunk
            when (id) {
                "fmt " -> if (size >= 16) {
                    channels = le16(raw, body + 2)
                    sampleRate = le32(raw, body + 4)
                    bits = le16(raw, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = size
                    break
                }
            }
            offset = body + size + (size and 1) // RIFF chunks are word aligned
        }
        if (dataOffset < 0 || dataSize < 2) return null
        if (bits != 16 || channels !in 1..2 || sampleRate <= 0) return null
        val frames = dataSize / (2 * channels)
        if (frames <= 0) return null
        val samples = IntArray(frames)
        for (i in 0 until frames) {
            val base = dataOffset + i * channels * 2
            var sum = 0
            for (c in 0 until channels) sum += sampleAt(raw, base + c * 2)
            // A stereo bed still plays as music instead of shifting into garbage.
            samples[i] = sum / channels
        }
        return WavPcm(samples, sampleRate, channels, bits, hasHeader = true)
    }

    private fun barePcm(raw: ByteArray): WavPcm? {
        val n = raw.size / 2
        if (n <= 0) return null
        return WavPcm(IntArray(n) { sampleAt(raw, it * 2) }, FALLBACK_RATE, 1, 16, hasHeader = false)
    }

    /** 16-bit signed sample at [offset]; the low byte is masked, never sign-extended. */
    private fun sampleAt(raw: ByteArray, offset: Int): Int = le16(raw, offset).toShort().toInt()

    private fun le16(raw: ByteArray, offset: Int): Int =
        (raw[offset].toInt() and 0xFF) or ((raw[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(raw: ByteArray, offset: Int): Int =
        le16(raw, offset) or (le16(raw, offset + 2) shl 16)

    private fun tag(raw: ByteArray, offset: Int): String =
        String(raw, offset, 4, Charsets.US_ASCII)
}
