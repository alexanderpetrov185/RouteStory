package com.alexander.routestory.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Covers the pure audio maths of the export. A wrong sample grid here is exactly what made
 * the exported clip hiss, so the bed/cue placement, the resampling and the "noise-like"
 * metric are all pinned by tests.
 */
class AudioMixTest {

    private fun samplesOf(pcm: ByteArray): List<Int> =
        (0 until pcm.size / 2).map { i ->
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt() and 0xFF
            ((hi shl 8) or lo).toShort().toInt()
        }

    @Test
    fun `bed is tiled across the whole clip`() {
        val bed = intArrayOf(100, -200, 300, -400)
        val pcm = AudioMix.mix(bed, 1f, emptyList(), frameRate = 10, sampleRate = 100, totalSamples = 10)
        assertEquals(
            listOf(100, -200, 300, -400, 100, -200, 300, -400, 100, -200),
            samplesOf(pcm)
        )
    }

    @Test
    fun `cue lands on the sample of its frame`() {
        val cue = AudioMix.Cue(frame = 5, samples = intArrayOf(7, -8, 9), gain = 1f)
        val pcm = AudioMix.mix(
            intArrayOf(0), 1f, listOf(cue), frameRate = 10, sampleRate = 100, totalSamples = 100
        )
        val samples = samplesOf(pcm)
        assertEquals(0, samples[49])
        assertEquals(listOf(7, -8, 9), samples.subList(50, 53))
        assertEquals(0, samples[53])
    }

    @Test
    fun `cue overlapping the clip end is trimmed, not wrapped`() {
        val cue = AudioMix.Cue(frame = 9, samples = intArrayOf(1000, 1000, 1000, 1000), gain = 1f)
        val pcm = AudioMix.mix(
            intArrayOf(0), 1f, listOf(cue), frameRate = 10, sampleRate = 100, totalSamples = 100
        )
        val samples = samplesOf(pcm)
        // Frame 9 of a 10-frame clip: the cue starts at sample 90 and its tail is cut off
        // at the end of the clip instead of wrapping to sample 0.
        assertEquals(0, samples[0])
        assertEquals(listOf(1000, 1000, 1000, 1000), samples.subList(90, 94))
        assertEquals(0, samples[94])
        assertEquals(0, samples[99])
    }

    @Test
    fun `overflowing mix is scaled down instead of clipped`() {
        val cue = AudioMix.Cue(frame = 0, samples = intArrayOf(30_000), gain = 1f)
        val pcm = AudioMix.mix(
            intArrayOf(30_000), 1f, listOf(cue), frameRate = 10, sampleRate = 100, totalSamples = 4
        )
        val samples = samplesOf(pcm)
        assertEquals(AudioMix.FULL_SCALE, samples[0])
        for (s in samples) {
            assertTrue("sample $s outside full scale", s in -AudioMix.FULL_SCALE..AudioMix.FULL_SCALE)
        }
        // One shared scale factor for the whole mix: the untouched bed keeps its level
        // (rounded, hence the one-LSB tolerance).
        assertTrue("bed level ${samples[1]} lost the shared scale", kotlin.math.abs(samples[1] - samples[0] / 2) <= 1)
    }

    @Test
    fun `resample doubles the rate with linear interpolation`() {
        val out = AudioMix.resample(intArrayOf(0, 100, 200), fromRate = 10, toRate = 20)
        assertEquals(listOf(0, 50, 100, 150, 200, 200), out.toList())
    }

    @Test
    fun `resample keeps the rate when nothing changes`() {
        val samples = intArrayOf(5, -5, 15)
        assertTrue(AudioMix.resample(samples, 22_050, 22_050) === samples)
    }

    @Test
    fun `meter separates music from noise`() {
        val rate = 44_100
        val music = IntArray(rate) { (10_000 * sin(2 * PI * 440 * it / rate)).toInt() }
        val musicStats = AudioMix.measure(music, rate)
        assertFalse("music must not look like noise", musicStats.isNoiseLike(rate))
        assertTrue("music rms too low: ${musicStats.rms}", musicStats.rms > 5_000.0)
        assertEquals(1_000L, musicStats.durationMs)

        var seed = 1L
        val noise = IntArray(rate) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            (seed shr 40).toInt() % 32_768
        }
        val noiseStats = AudioMix.measure(noise, rate)
        assertTrue(
            "noise must be flagged, crossPerSec=${noiseStats.crossingsPerSecond}",
            noiseStats.isNoiseLike(rate)
        )
    }

    @Test
    fun `wav container round trips through the strict parser`() {
        val pcm = AudioMix.mix(intArrayOf(1234, -1234), 1f, emptyList(), 10, 100, 5)
        val parsed = WavPcmDecoder.parse(AudioMix.wavBytes(pcm, 100))!!
        assertEquals(100, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertTrue(parsed.hasHeader)
        assertEquals(samplesOf(pcm), parsed.samples.toList())
    }
}
