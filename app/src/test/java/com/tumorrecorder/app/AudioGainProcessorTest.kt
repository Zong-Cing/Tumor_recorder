package com.tumorrecorder.app

import com.tumorrecorder.app.domain.model.VoiceBoostLevel
import com.tumorrecorder.app.service.audio.AudioGainProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioGainProcessorTest {

    private val sampleRate = 44_100
    private val frameSize = sampleRate / 50 // 20 ms = 882 samples

    private fun generateTone(amplitude: Double, freqHz: Double = 600.0, frames: Int = 1): ShortArray {
        val total = frameSize * frames
        return ShortArray(total) { i ->
            (sin(2.0 * PI * freqHz * i / sampleRate) * amplitude * 32767.0).toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }
    }

    private fun computeRms(samples: ShortArray): Double {
        var sumSq = 0.0
        for (s in samples) {
            val n = s / 32768.0
            sumSq += n * n
        }
        return sqrt(sumSq / samples.size)
    }

    @Test
    fun strongBoostDramaticallyAmplifiesQuietRemoteVoice() {
        val processor = AudioGainProcessor(sampleRate, VoiceBoostLevel.STRONG)
        // Simulate faint remote party at ~-38 dBFS (amplitude ≈ 0.018)
        val rawInput = generateTone(amplitude = 0.018)
        val inputRms = computeRms(rawInput)

        var lastOutput = rawInput.copyOf()
        var lastGainDb = 0f
        // Feed 25 frames (500 ms) so release envelope settles
        repeat(25) {
            val buf = rawInput.copyOf()
            val metrics = processor.processInPlace(buf, buf.size)
            assertFalse(metrics.isAllZeros)
            lastOutput = buf
            lastGainDb = metrics.appliedGainDb
        }

        val boostedRms = computeRms(lastOutput)
        val ratio = boostedRms / inputRms
        assertTrue("Expected >= 8x RMS boost for quiet voice, got ${"%.2f".format(ratio)}x", ratio >= 8.0)
        assertTrue("Expected appliedGainDb >= 18 dB, got $lastGainDb dB", lastGainDb >= 18f)
    }

    @Test
    fun suddenLoudLocalVoiceAfterQuietRemoteDoesNotClip() {
        val processor = AudioGainProcessor(sampleRate, VoiceBoostLevel.STRONG)
        // Prime AGC to high gain with quiet remote voice
        val quiet = generateTone(amplitude = 0.015)
        repeat(25) {
            processor.processInPlace(quiet.copyOf(), quiet.size)
        }

        // Suddenly speak loudly right into microphone (-1.5 dBFS)
        val loud = generateTone(amplitude = 0.85)
        val metrics = processor.processInPlace(loud, loud.size)
        assertFalse(metrics.isAllZeros)

        val maxAbs = loud.maxOf { abs(it.toInt()) }
        assertTrue("Expected soft-limited peak <= 32500 without hard clipping, got $maxAbs", maxAbs <= 32500)
    }

    @Test
    fun allZeroBufferReportsSilenceFlag() {
        val processor = AudioGainProcessor(sampleRate, VoiceBoostLevel.STRONG)
        val zeros = ShortArray(frameSize)
        val metrics = processor.processInPlace(zeros, zeros.size)
        assertTrue(metrics.isAllZeros)
        assertEquals(0f, metrics.rawPeakNormalized, 0.0001f)
    }

    @Test
    fun noiseGatePreventsBoostingDeadSilenceHiss() {
        val processor = AudioGainProcessor(sampleRate, VoiceBoostLevel.STRONG)
        // Very quiet -66 dBFS background floor (amplitude ≈ 0.0007)
        val hiss = generateTone(amplitude = 0.0007)
        var lastGainDb = 0f
        repeat(20) {
            val metrics = processor.processInPlace(hiss.copyOf(), hiss.size)
            lastGainDb = metrics.appliedGainDb
        }
        assertTrue("Expected noise gate to keep gain near 0 dB for -66 dBFS hiss, got $lastGainDb dB", lastGainDb < 3f)
    }

    @Test
    fun offModeLeavesSamplesUntouched() {
        val processor = AudioGainProcessor(sampleRate, VoiceBoostLevel.OFF)
        val original = generateTone(amplitude = 0.05)
        val copy = original.copyOf()
        val metrics = processor.processInPlace(copy, copy.size)
        assertEquals(0f, metrics.appliedGainDb, 0.0001f)
        assertTrue(original.contentEquals(copy))
    }
}
