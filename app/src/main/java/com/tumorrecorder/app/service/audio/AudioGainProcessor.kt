package com.tumorrecorder.app.service.audio

import com.tumorrecorder.app.domain.model.VoiceBoostLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

data class AudioFrameMetrics(
    val rawPeakNormalized: Float,
    val outputRmsDb: Float,
    val appliedGainDb: Float,
    val isAllZeros: Boolean
)

/**
 * Pure-Kotlin speech-band Dynamic Range Compressor + Lookahead Automatic Gain Control (AGC)
 * tailored for call recordings where the remote party (earpiece acoustic pickup) is 20–40 dB
 * quieter than the local speaker.
 *
 * Processing stages per frame (~20 ms lookahead):
 * 1. First-order high-pass filter (~120 Hz) to remove DC offset, hand-holding bumps, and low rumble.
 * 2. Frame RMS + Peak inspection before gain application (zero-overshoot lookahead limiting).
 * 3. Smooth noise-gate taper (-60 dBFS to -50 dBFS) so dead silence isn't boosted into loud hiss,
 *    while even whisper-quiet remote voices (-46 dBFS to -28 dBFS) receive full AGC boost up to +30 dB.
 * 4. Fast attack / smooth release gain envelope interpolated sample-by-sample across the frame
 *    followed by a soft-knee tanh ceiling limiter to prevent clipping on loud near-end speech.
 */
class AudioGainProcessor(
    private val sampleRate: Int,
    private val boostLevel: VoiceBoostLevel
) {
    private val maxBoostDb: Double = boostLevel.maxBoostDb
    private val highPassAlpha: Double = run {
        val rc = 1.0 / (2.0 * PI * HIGH_PASS_CUTOFF_HZ)
        val dt = 1.0 / sampleRate.coerceAtLeast(8000)
        rc / (rc + dt)
    }

    private var prevRawInput: Double = 0.0
    private var prevFilteredOutput: Double = 0.0
    private var currentGain: Double = 1.0
    private var scratch = DoubleArray(0)

    fun processInPlace(samples: ShortArray, count: Int): AudioFrameMetrics {
        if (count <= 0) {
            return AudioFrameMetrics(
                rawPeakNormalized = 0f,
                outputRmsDb = -96f,
                appliedGainDb = 0f,
                isAllZeros = true
            )
        }

        var rawPeak = 0.0
        var anyNonZero = false
        for (i in 0 until count) {
            val s = samples[i].toInt()
            if (s != 0) anyNonZero = true
            val a = abs(s) / 32768.0
            if (a > rawPeak) rawPeak = a
        }

        if (!anyNonZero) {
            // Smoothly return gain toward 1.0 during absolute digital silence
            currentGain += (1.0 - currentGain) * 0.1
            return AudioFrameMetrics(
                rawPeakNormalized = 0f,
                outputRmsDb = -96f,
                appliedGainDb = 0f,
                isAllZeros = true
            )
        }

        if (boostLevel == VoiceBoostLevel.OFF || maxBoostDb <= 0.0) {
            var sumSq = 0.0
            for (i in 0 until count) {
                val x = samples[i] / 32768.0
                sumSq += x * x
            }
            val rms = sqrt(sumSq / count)
            val rmsDb = (20.0 * log10(max(rms, 1e-6))).toFloat()
            return AudioFrameMetrics(
                rawPeakNormalized = rawPeak.toFloat(),
                outputRmsDb = rmsDb,
                appliedGainDb = 0f,
                isAllZeros = false
            )
        }

        if (scratch.size < count) {
            scratch = DoubleArray(count)
        }

        // Stage 1: 120 Hz High-Pass Filter + RMS / Peak measurement
        var sumSq = 0.0
        var filteredPeak = 0.0
        var prevX = prevRawInput
        var prevY = prevFilteredOutput
        val alpha = highPassAlpha

        for (i in 0 until count) {
            val x = samples[i] / 32768.0
            val y = alpha * (prevY + x - prevX)
            prevX = x
            prevY = y
            scratch[i] = y
            sumSq += y * y
            val a = abs(y)
            if (a > filteredPeak) filteredPeak = a
        }
        prevRawInput = prevX
        prevFilteredOutput = prevY

        val frameRms = sqrt(sumSq / count)
        val frameRmsDb = 20.0 * log10(max(frameRms, 1e-6))

        // Stage 2: Target-level AGC with smooth noise-gate taper
        val rawDesiredGainDb = (TARGET_RMS_DB - frameRmsDb).coerceIn(MIN_GAIN_DB, maxBoostDb)
        val desiredGainDb = if (rawDesiredGainDb > 0.0 && frameRmsDb < NOISE_GATE_HIGH_DB) {
            val gateFactor = ((frameRmsDb - NOISE_GATE_LOW_DB) / (NOISE_GATE_HIGH_DB - NOISE_GATE_LOW_DB))
                .coerceIn(0.0, 1.0)
            rawDesiredGainDb * gateFactor
        } else {
            rawDesiredGainDb
        }

        var targetGain = 10.0.pow(desiredGainDb / 20.0)

        // Stage 3: Lookahead peak ceiling so transients never hard-clip
        if (filteredPeak > 1e-6) {
            val maxSafeGain = CEILING_LINEAR / filteredPeak
            targetGain = min(targetGain, maxSafeGain)
        }

        // Stage 4: Fast attack (gain decreasing), smooth release (gain increasing)
        val frameDurationSec = count.toDouble() / sampleRate.coerceAtLeast(8000)
        val startGain = currentGain
        val endGain = if (targetGain < startGain) {
            // Instant peak protection: drop immediately enough to respect ceiling,
            // otherwise fast attack time constant (~15 ms)
            val attackCoeff = 1.0 - exp(-frameDurationSec / ATTACK_TIME_SEC)
            val smoothed = startGain + (targetGain - startGain) * attackCoeff
            if (filteredPeak * smoothed > CEILING_LINEAR) targetGain else smoothed
        } else {
            val releaseCoeff = 1.0 - exp(-frameDurationSec / RELEASE_TIME_SEC)
            startGain + (targetGain - startGain) * releaseCoeff
        }

        // If even startGain would clip early samples in this frame, snap startGain safely down
        val safeStartGain = if (filteredPeak > 1e-6 && filteredPeak * startGain > 0.96) {
            min(startGain, 0.96 / filteredPeak)
        } else {
            startGain
        }

        // Stage 5: Sample-by-sample interpolated gain + soft-knee limiter
        val invCount = 1.0 / count
        var outSumSq = 0.0
        for (i in 0 until count) {
            val t = i * invCount
            val g = safeStartGain + (endGain - safeStartGain) * t
            val amplified = scratch[i] * g
            val limited = softLimit(amplified)
            outSumSq += limited * limited
            val pcm = (limited * 32767.0).roundToInt().coerceIn(-32768, 32767)
            samples[i] = pcm.toShort()
        }

        currentGain = endGain

        val outRms = sqrt(outSumSq / count)
        val outRmsDb = (20.0 * log10(max(outRms, 1e-6))).toFloat()
        val appliedGainDb = (20.0 * ln(max(endGain, 1e-6)) / ln(10.0)).toFloat()

        return AudioFrameMetrics(
            rawPeakNormalized = rawPeak.toFloat(),
            outputRmsDb = outRmsDb,
            appliedGainDb = appliedGainDb,
            isAllZeros = false
        )
    }

    private fun softLimit(x: Double): Double {
        val absX = abs(x)
        if (absX <= SOFT_KNEE_START) return x
        val excess = absX - SOFT_KNEE_START
        val headroom = 1.0 - SOFT_KNEE_START
        val compressed = SOFT_KNEE_START + headroom * tanh(excess / headroom)
        return if (x >= 0.0) compressed else -compressed
    }

    companion object {
        private const val HIGH_PASS_CUTOFF_HZ = 120.0
        private const val TARGET_RMS_DB = -14.0
        private const val MIN_GAIN_DB = -6.0
        private const val NOISE_GATE_LOW_DB = -60.0
        private const val NOISE_GATE_HIGH_DB = -50.0
        private const val CEILING_LINEAR = 0.88
        private const val SOFT_KNEE_START = 0.85
        private const val ATTACK_TIME_SEC = 0.015
        private const val RELEASE_TIME_SEC = 0.140
    }
}
