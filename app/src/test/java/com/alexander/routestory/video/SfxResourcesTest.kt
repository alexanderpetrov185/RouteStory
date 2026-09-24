package com.alexander.routestory.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the shipped SFX cues: a corrupted resource or a misaligned grid would hiss over
 * the whole clip, so the real res/raw files are measured with the same metrics the export
 * uses to drop a noise-like cue (see VideoExportUseCase.readSfxCue).
 */
class SfxResourcesTest {

    /** The export encodes at 44.1 kHz; the cue is measured there after resampling. */
    private companion object {
        const val AUDIO_SAMPLE_RATE = 44_100
    }


    private fun resolve(name: String): File {
        val candidates = listOf(
            File("src/main/res/raw/$name"),
            File("app/src/main/res/raw/$name"),
            File("../app/src/main/res/raw/$name")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("$name not found, tried " + candidates.joinToString { it.absolutePath })
    }

    @Test
    fun `whoosh cue is a short pass-by, not white noise`() {
        val parsed = WavPcmDecoder.parse(resolve("whoosh.wav").readBytes())!!
        assertEquals(22_050, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertTrue(parsed.hasHeader)
        // ~0.8 s at 22.05 kHz (the old wind gust was 1 s of band noise).
        assertTrue("unexpected length ${parsed.samples.size}", parsed.samples.size in 15_000..20_000)
        val stats = AudioMix.measure(parsed.samples, parsed.sampleRate)
        assertFalse(
            "whoosh looks like noise: crossPerSec=${stats.crossingsPerSecond}",
            stats.isNoiseLike(parsed.sampleRate)
        )
        assertTrue("whoosh peak ${stats.peak}", stats.peak in 8_000..14_000)
        assertTrue("whoosh is silent: rms ${stats.rms}", stats.rms > 1_000.0)
    }

    @Test
    fun `shutter cue is a mechanical click and survives the noise check`() {
        val parsed = WavPcmDecoder.parse(resolve("shutter.wav").readBytes())!!
        assertEquals(22_050, parsed.sampleRate)
        assertEquals(16, parsed.bitsPerSample)
        // Sprint 43: CC0 mechanical camera sample, converted without synthesis.
        assertTrue("unexpected length ${parsed.samples.size}", parsed.samples.size in 1_500..8_000)
        val stats = AudioMix.measure(parsed.samples, parsed.sampleRate)
        assertTrue("shutter peak ${stats.peak}", stats.peak in 2_000..15_000)
        assertTrue("shutter is silent: rms ${stats.rms}", stats.rms > 200.0)
        // The export drops a noise-like cue (see VideoExportUseCase.readSfxCue), so a
        // broadband hiss would silence every photo: the tail has to keep the
        // zero-crossing rate under the noise threshold.
        assertFalse(
            "shutter looks like noise: crossPerSec=${stats.crossingsPerSecond}",
            stats.isNoiseLike(parsed.sampleRate)
        )
        assertFalse(
            "shutter is too close to the noise threshold: ${stats.crossingsPerSecond}",
            stats.isNoiseLike(AUDIO_SAMPLE_RATE)
        )
    }
}
