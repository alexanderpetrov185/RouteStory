package com.alexander.routestory.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Covers the two ways a WAV read used to turn the audio into garbage:
 *  1. `Byte.toInt()` on the LOW PCM byte sign-extends it, so any sample with a low byte
 *     >= 0x80 kept 0xFF in the high byte after `.toShort()` (half of the track became noise);
 *  2. a blind 44-byte header skip: any extra chunk ("LIST"/"fact"), a different fmt size or
 *     non-16-bit data pushed the payload elsewhere, so every sample was assembled from
 *     halves of two neighbours (broadband noise over the whole clip).
 * The parser now walks the real chunk list, and the last test checks the real res/raw/bgm.wav.
 */
class WavPcmDecoderTest {

    private fun wav(pcm: ByteArray, withHeader: Boolean = true): ByteArray {
        if (!withHeader) return pcm
        val out = ByteArrayOutputStream()
        val id = { s: String -> s.toByteArray(Charsets.US_ASCII) }
        out.write(id("RIFF"))
        val dataSize = pcm.size
        val size = 36 + dataSize
        out.write(
            byteArrayOf(
                (size and 0xFF).toByte(),
                ((size shr 8) and 0xFF).toByte(),
                ((size shr 16) and 0xFF).toByte(),
                ((size shr 24) and 0xFF).toByte()
            )
        )
        out.write(id("WAVEfmt "))
        out.write(byteArrayOf(16, 0, 0, 0)) // fmt chunk size
        out.write(byteArrayOf(1, 0, 1, 0)) // PCM, mono
        out.write(byteArrayOf(0x56, 0x22, 0, 0)) // 22050 Hz
        out.write(byteArrayOf(0x44, 0xAC.toByte(), 0, 0)) // byte rate
        out.write(byteArrayOf(2, 0, 0x10, 0)) // block align 2, 16 bits
        out.write(id("data"))
        out.write(
            byteArrayOf(
                (dataSize and 0xFF).toByte(),
                ((dataSize shr 8) and 0xFF).toByte(),
                ((dataSize shr 16) and 0xFF).toByte(),
                ((dataSize shr 24) and 0xFF).toByte()
            )
        )
        out.write(pcm)
        return out.toByteArray()
    }

    private fun le16(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )

    /** The exact samples the old decoder corrupted: low byte >= 0x80 with high byte < 0x80. */
    @Test
    fun `low byte with sign extension is decoded unsigned`() {
        val raw = le16(0x20C0.toShort()) + le16(0x00C0.toShort()) + le16(0x0080.toShort()) + le16(0x7FC0.toShort())
        val samples = WavPcmDecoder.decode(raw, skipHeader = false)
        // Old buggy path turned these into 0xFFC0 / 0xFF80 / 0xFFC0 (negative noise).
        assertEquals(listOf(8384, 192, 128, 32704), samples.toList())
    }

    @Test
    fun `negative samples survive round trip`() {
        val raw = le16((-1).toShort()) + le16((-2).toShort()) + le16((-32768).toShort()) + le16(32767.toShort())
        val samples = WavPcmDecoder.decode(raw, skipHeader = false)
        assertEquals(listOf(-1, -2, -32768, 32767), samples.toList())
    }

    @Test
    fun `canonical wav header is skipped, bare pcm is not`() {
        val pcm = le16(5) + le16(-5)
        val withHeader = WavPcmDecoder.decode(wav(pcm))
        val bare = WavPcmDecoder.decode(pcm, skipHeader = true)
        assertEquals(listOf(5, -5), withHeader.toList())
        // The bare byte array has no RIFF signature, so nothing is skipped and both
        // copies decode identically (old code shifted these by 44 garbage samples).
        assertEquals(withHeader.toList(), bare.toList())
    }

    @Test
    fun `header detection`() {
        assertTrue(WavPcmDecoder.hasWavHeader(wav(le16(1))))
        assertFalse(WavPcmDecoder.hasWavHeader(le16(1)))
        // Too short to contain a header.
        assertEquals(0, WavPcmDecoder.decode(byteArrayOf(0x01), skipHeader = false).size)
        assertEquals(0, WavPcmDecoder.decode(ByteArray(0), skipHeader = false).size)
    }

    @Test
    fun `extra chunk before data does not shift the sample grid`() {
        val data = le16(5) + le16(-5) + le16(1000)
        val parsed = WavPcmDecoder.parse(
            riff(
                chunk("fmt ", pcmFmt(1, 1, 22_050, 16)),
                // Odd-sized chunk: the payload no longer starts at byte 44.
                chunk("LIST", byteArrayOf(1, 2, 3, 4, 5)),
                chunk("data", data)
            )
        )!!
        assertEquals(listOf(5, -5, 1000), parsed.samples.toList())
        assertEquals(22_050, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        // The blind 44-byte skip would have read the LIST chunk as PCM.
        assertEquals(3, parsed.samples.size)
    }

    @Test
    fun `24 bit data is rejected instead of misaligned`() {
        val wav = riff(
            chunk("fmt ", pcmFmt(1, 1, 22_050, 24)),
            chunk("data", ByteArray(9))
        )
        assertNull(WavPcmDecoder.parse(wav))
    }

    @Test
    fun `stereo data is downmixed to mono`() {
        val data = le16(100) + le16(300) + le16(-100) + le16(-300)
        val parsed = WavPcmDecoder.parse(
            riff(chunk("fmt ", pcmFmt(1, 2, 44_100, 16)), chunk("data", data))
        )!!
        assertEquals(2, parsed.channels)
        assertEquals(44_100, parsed.sampleRate)
        assertEquals(listOf(200, -200), parsed.samples.toList())
    }

    @Test
    fun `truncated data chunk is refused`() {
        // Header claims more PCM than the buffer holds: decoding it would shift the grid.
        val header = riff(chunk("fmt ", pcmFmt(1, 1, 22_050, 16)), chunk("data", ByteArray(0)))
        assertNull(WavPcmDecoder.parse(header))
    }

    /**
     * Guards the "white noise instead of music" class of defects on the real resource: a
     * misaligned grid or a wrong bit depth shows up as a huge zero-crossing rate.
     */
    @Test
    fun `real bgm wav decodes to music-like samples`() {
        val parsed = WavPcmDecoder.parse(bgmFile().readBytes())!!
        assertEquals(22_050, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertTrue(parsed.hasHeader)
        assertEquals(352_800, parsed.samples.size) // 16 s mono at 22.05 kHz

        val stats = AudioMix.measure(parsed.samples, parsed.sampleRate)
        assertFalse(
            "bgm looks like noise: crossPerSec=${stats.crossingsPerSecond}",
            stats.isNoiseLike(parsed.sampleRate)
        )
        assertTrue("bgm peak too low: ${stats.peak}", stats.peak in 5_000..AudioMix.FULL_SCALE)
        assertTrue("bgm rms out of range: ${stats.rms}", stats.rms in 500.0..16_000.0)
    }

    /** 16-byte canonical fmt chunk body. */
    private fun pcmFmt(format: Int, channels: Int, rate: Int, bits: Int): ByteArray {
        val blockAlign = channels * bits / 8
        return le16(format.toShort()) + le16(channels.toShort()) +
            le32(rate) + le32(rate * blockAlign) +
            le16(blockAlign.toShort()) + le16(bits.toShort())
    }

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    /** One RIFF chunk with the word-alignment padding byte when the body size is odd. */
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(le32(body.size))
        out.write(body)
        if (body.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        chunks.forEach { payload.write(it) }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(4 + payload.size()))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write(payload.toByteArray())
        return out.toByteArray()
    }

    /** The real generated bed; unit tests run with the module directory as CWD. */
    private fun bgmFile(): File {
        val candidates = listOf(
            File("src/main/res/raw/bgm.wav"),
            File("app/src/main/res/raw/bgm.wav"),
            File("../app/src/main/res/raw/bgm.wav")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("bgm.wav not found, tried " + candidates.joinToString { it.absolutePath })
    }
}
