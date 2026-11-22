package com.tumorrecorder.app.service.audio

import com.tumorrecorder.app.domain.model.VoiceBoostLevel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class MasteringReport(
    val totalFrames: Int,
    val channelCount: Int,
    val dualMicDistinct: Boolean,
    val noiseFloorDb: Float,
    val speechPeakDb: Float,
    val maxAppliedGainDb: Float,
    val avgSpeechGainDb: Float
)

/**
 * Pure-Kotlin 2-pass post-call speech mastering engine:
 *
 * Pass 1 (Global Analysis):
 * - Runs a 250 Hz – 3800 Hz telephone speech bandpass filter on each channel independently
 *   to reject low-frequency handling rumble and high-frequency thermal hiss.
 * - For stereo dual-mic captures (bottom mic + top mic near earpiece), computes each mic's
 *   noise floor and per-frame SNR so each 20 ms frame smoothly favors whichever mic hears the
 *   speaker more cleanly.
 * - Estimates the empirical room/mic noise floor (15th percentile) across the whole call.
 *
 * Gain Curve Planning (Zero-Lag Forward + Backward Smoothing):
 * - Attenuates noise-floor pauses (-6 to -10 dB) so background hiss is suppressed rather than boosted.
 * - Levels quiet remote speech up toward -14 dBFS (up to +24 dB STANDARD / +38 dB STRONG).
 * - Uses backward lookahead so loud near-end words never pop/clip and quiet far-end first syllables
 *   are never cut off.
 *
 * Pass 2 (Streaming Render):
 * - Applies bandpass + +3 dB speech presence EQ (2 kHz) + sample-interpolated gain + soft tanh limiter.
 */
class SpeechMasteringProcessor(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val boostLevel: VoiceBoostLevel
) {
    private val channels = channelCount.coerceIn(1, 2)
    private val frameSamples = (sampleRate / 50).coerceAtLeast(320) // 20 ms per frame

    fun processPcmFile(
        rawPcmFile: File,
        onMasteredChunk: (samples: ShortArray, count: Int) -> Unit
    ): MasteringReport {
        val bytesPerFrame = frameSamples * channels * 2
        val fileLen = rawPcmFile.length()
        val estimatedFrames = ((fileLen + bytesPerFrame - 1L) / bytesPerFrame).toInt().coerceAtLeast(1)

        // Pass 1: Scan entire file in streaming 20 ms chunks
        val ch0RmsList = FloatArray(estimatedFrames)
        val ch1RmsList = if (channels == 2) FloatArray(estimatedFrames) else FloatArray(0)
        val ch0PeakList = FloatArray(estimatedFrames)
        val ch1PeakList = if (channels == 2) FloatArray(estimatedFrames) else FloatArray(0)
        var diffEnergySum = 0.0
        var totalEnergySum = 0.0

        val pass1Filter0 = SpeechBandpassFilter(sampleRate)
        val pass1Filter1 = if (channels == 2) SpeechBandpassFilter(sampleRate) else null

        var frameCount = 0
        val ioBuf = ByteArray(bytesPerFrame)

        BufferedInputStream(FileInputStream(rawPcmFile), 65_536).use { input ->
            while (frameCount < estimatedFrames) {
                val bytesRead = readFullChunk(input, ioBuf, bytesPerFrame)
                if (bytesRead < channels * 2) break
                val samplesPerCh = bytesRead / (channels * 2)

                var sumSq0 = 0.0
                var peak0 = 0.0
                var sumSq1 = 0.0
                var peak1 = 0.0

                var idx = 0
                for (i in 0 until samplesPerCh) {
                    val s0 = readShortLE(ioBuf, idx) / 32768.0
                    idx += 2
                    val f0 = pass1Filter0.process(s0)
                    sumSq0 += f0 * f0
                    val a0 = abs(f0)
                    if (a0 > peak0) peak0 = a0

                    if (channels == 2) {
                        val s1 = readShortLE(ioBuf, idx) / 32768.0
                        idx += 2
                        val f1 = pass1Filter1!!.process(s1)
                        sumSq1 += f1 * f1
                        val a1 = abs(f1)
                        if (a1 > peak1) peak1 = a1
                        val diff = f0 - f1
                        diffEnergySum += diff * diff
                        totalEnergySum += f0 * f0 + f1 * f1
                    }
                }

                val rms0 = sqrt(sumSq0 / samplesPerCh)
                ch0RmsList[frameCount] = linearToDb(rms0).toFloat()
                ch0PeakList[frameCount] = peak0.toFloat()

                if (channels == 2) {
                    val rms1 = sqrt(sumSq1 / samplesPerCh)
                    ch1RmsList[frameCount] = linearToDb(rms1).toFloat()
                    ch1PeakList[frameCount] = peak1.toFloat()
                }
                frameCount++
            }
        }

        if (frameCount == 0) {
            return MasteringReport(0, channels, false, -96f, -96f, 0f, 0f)
        }

        val dualMicDistinct = channels == 2 &&
            totalEnergySum > 1e-6 &&
            (diffEnergySum / totalEnergySum) > 0.02

        val floor0 = estimateNoiseFloorDb(ch0RmsList, frameCount)
        val floor1 = if (dualMicDistinct) estimateNoiseFloorDb(ch1RmsList, frameCount) else floor0

        // Per-frame adaptive channel weights (w1 = weight of channel 1; w0 = 1 - w1)
        val ch1Weights = FloatArray(frameCount)
        val combinedRmsDb = FloatArray(frameCount)
        val combinedPeak = FloatArray(frameCount)

        for (f in 0 until frameCount) {
            if (dualMicDistinct) {
                val snr0 = ch0RmsList[f] - floor0
                val snr1 = ch1RmsList[f] - floor1
                // Smooth logistic preference toward whichever mic has stronger SNR on this 20 ms frame
                val delta = (snr1 - snr0).coerceIn(-18f, 18f)
                val rawW1 = (1.0 / (1.0 + kotlin.math.exp(-delta * 0.45))).toFloat()
                ch1Weights[f] = rawW1
            } else {
                ch1Weights[f] = if (channels == 2) 0.5f else 0f
            }
        }

        // Smooth channel blending weights forward & backward to prevent stereo switching artifacts
        if (dualMicDistinct) {
            smoothArrayBidirectional(ch1Weights, frameCount, alphaForward = 0.25f, alphaBackward = 0.25f)
        }

        for (f in 0 until frameCount) {
            if (channels == 2) {
                val w1 = ch1Weights[f]
                val w0 = 1f - w1
                val linRms = w0 * dbToLinear(ch0RmsList[f].toDouble()) + w1 * dbToLinear(ch1RmsList[f].toDouble())
                combinedRmsDb[f] = linearToDb(linRms).toFloat()
                combinedPeak[f] = (w0 * ch0PeakList[f] + w1 * ch1PeakList[f]).coerceAtLeast(1e-6f)
            } else {
                combinedRmsDb[f] = ch0RmsList[f]
                combinedPeak[f] = ch0PeakList[f].coerceAtLeast(1e-6f)
            }
        }

        val globalNoiseFloorDb = estimateNoiseFloorDb(combinedRmsDb, frameCount)
        val speechPeakDb = estimatePercentileDb(combinedRmsDb, frameCount, 0.95)

        // Build zero-lag gain curve (in dB)
        val gainDbCurve = FloatArray(frameCount)
        val maxBoost = boostLevel.maxBoostDb
        val noiseAttenDb = when (boostLevel) {
            VoiceBoostLevel.OFF -> 0.0
            VoiceBoostLevel.STANDARD -> -6.0
            VoiceBoostLevel.STRONG -> -10.0
        }

        var maxAppliedGainDb = 0f
        var speechGainSum = 0.0
        var speechFrameCount = 0

        if (boostLevel != VoiceBoostLevel.OFF && maxBoost > 0.0) {
            for (f in 0 until frameCount) {
                val rmsDb = combinedRmsDb[f].toDouble()
                val peak = combinedPeak[f].toDouble()
                val snrDb = rmsDb - globalNoiseFloorDb

                val rawGainDb = when {
                    rmsDb <= -90.0 -> 0.0
                    snrDb <= NOISE_GATE_SNR_LOW_DB -> noiseAttenDb
                    snrDb < NOISE_GATE_SNR_HIGH_DB -> {
                        val t = ((snrDb - NOISE_GATE_SNR_LOW_DB) / (NOISE_GATE_SNR_HIGH_DB - NOISE_GATE_SNR_LOW_DB))
                            .coerceIn(0.0, 1.0)
                        val smoothT = t * t * (3.0 - 2.0 * t) // smoothstep
                        val speechTargetGain = (TARGET_RMS_DB - rmsDb).coerceIn(MIN_GAIN_DB, maxBoost)
                        noiseAttenDb + (speechTargetGain - noiseAttenDb) * smoothT
                    }
                    else -> {
                        (TARGET_RMS_DB - rmsDb).coerceIn(MIN_GAIN_DB, maxBoost)
                    }
                }

                // Respect peak headroom before smoothing
                val ceilingGainDb = if (peak > 1e-6) {
                    20.0 * log10(CEILING_LINEAR / peak)
                } else {
                    maxBoost
                }
                gainDbCurve[f] = min(rawGainDb, ceilingGainDb).toFloat()
            }

            // Backward pass: lookahead so loud words drop gain BEFORE arrival & quiet syllables rise cleanly
            for (f in frameCount - 2 downTo 0) {
                val next = gainDbCurve[f + 1]
                val curr = gainDbCurve[f]
                gainDbCurve[f] = if (next < curr) {
                    // Fast lookahead ducking (~30 ms) before a loud transient
                    curr + (next - curr) * 0.55f
                } else {
                    // Gentle pre-ramp (~50 ms) before a quiet remote syllable
                    curr + (next - curr) * 0.30f
                }
            }

            // Forward pass: smooth natural release (~140 ms) to avoid choppy gating/pumping
            for (f in 1 until frameCount) {
                val prev = gainDbCurve[f - 1]
                val curr = gainDbCurve[f]
                gainDbCurve[f] = if (curr < prev) {
                    // Fast attack (~20 ms)
                    prev + (curr - prev) * 0.65f
                } else {
                    // Smooth release (~140 ms)
                    prev + (curr - prev) * 0.14f
                }
            }

            // Final strict peak-ceiling guard per frame
            for (f in 0 until frameCount) {
                val peak = combinedPeak[f].toDouble()
                if (peak > 1e-6) {
                    val maxSafeDb = (20.0 * log10(CEILING_LINEAR / peak)).toFloat()
                    if (gainDbCurve[f] > maxSafeDb) {
                        gainDbCurve[f] = maxSafeDb
                    }
                }
                val g = gainDbCurve[f]
                if (g > maxAppliedGainDb) maxAppliedGainDb = g
                if (combinedRmsDb[f] - globalNoiseFloorDb >= NOISE_GATE_SNR_HIGH_DB) {
                    speechGainSum += g
                    speechFrameCount++
                }
            }
        }

        // Pass 2: Stream raw PCM, apply bandpass + presence EQ + adaptive dual-mic blend + smooth gain
        val pass2Filter0 = SpeechBandpassFilter(sampleRate)
        val pass2Filter1 = if (channels == 2) SpeechBandpassFilter(sampleRate) else null
        val presenceEq = if (boostLevel != VoiceBoostLevel.OFF) PresenceEqFilter(sampleRate) else null
        val outShorts = ShortArray(frameSamples)

        var prevGainLin = dbToLinear(gainDbCurve[0].toDouble())
        var prevW1 = ch1Weights[0].toDouble()
        var fIndex = 0

        BufferedInputStream(FileInputStream(rawPcmFile), 65_536).use { input ->
            while (fIndex < frameCount) {
                val bytesRead = readFullChunk(input, ioBuf, bytesPerFrame)
                if (bytesRead < channels * 2) break
                val samplesPerCh = bytesRead / (channels * 2)

                val targetGainLin = dbToLinear(gainDbCurve[fIndex].toDouble())
                val framePeak = combinedPeak[fIndex].toDouble()
                val safeStartGain = if (framePeak > 1e-6 && framePeak * prevGainLin > CEILING_LINEAR) {
                    min(prevGainLin, CEILING_LINEAR / framePeak)
                } else {
                    prevGainLin
                }
                val targetW1 = ch1Weights[fIndex].toDouble()
                val invSamples = 1.0 / samplesPerCh

                var idx = 0
                for (i in 0 until samplesPerCh) {
                    val t = i * invSamples
                    val s0 = readShortLE(ioBuf, idx) / 32768.0
                    idx += 2

                    val mixed = if (channels == 2) {
                        val s1 = readShortLE(ioBuf, idx) / 32768.0
                        idx += 2
                        if (boostLevel == VoiceBoostLevel.OFF) {
                            (s0 + s1) * 0.5
                        } else {
                            val f0 = pass2Filter0.process(s0)
                            val f1 = pass2Filter1!!.process(s1)
                            val w1 = prevW1 + (targetW1 - prevW1) * t
                            f0 * (1.0 - w1) + f1 * w1
                        }
                    } else {
                        if (boostLevel == VoiceBoostLevel.OFF) s0 else pass2Filter0.process(s0)
                    }

                    val outputSample = if (boostLevel == VoiceBoostLevel.OFF) {
                        mixed
                    } else {
                        val eqSample = presenceEq!!.process(mixed)
                        val g = safeStartGain + (targetGainLin - safeStartGain) * t
                        softLimit(eqSample * g)
                    }

                    outShorts[i] = (outputSample * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
                }

                prevGainLin = targetGainLin
                prevW1 = targetW1
                onMasteredChunk(outShorts, samplesPerCh)
                fIndex++
            }
        }

        val avgSpeechGain = if (speechFrameCount > 0) (speechGainSum / speechFrameCount).toFloat() else 0f
        return MasteringReport(
            totalFrames = frameCount,
            channelCount = channels,
            dualMicDistinct = dualMicDistinct,
            noiseFloorDb = globalNoiseFloorDb,
            speechPeakDb = speechPeakDb,
            maxAppliedGainDb = maxAppliedGainDb,
            avgSpeechGainDb = avgSpeechGain
        )
    }

    private fun estimateNoiseFloorDb(rmsDbList: FloatArray, count: Int): Float {
        val valid = ArrayList<Float>(count)
        for (i in 0 until count) {
            val v = rmsDbList[i]
            if (v > -89f) valid.add(v)
        }
        if (valid.isEmpty()) return -70f
        valid.sort()
        val p15Index = ((valid.size - 1) * 0.15).toInt().coerceIn(0, valid.lastIndex)
        return valid[p15Index].coerceIn(-78f, -34f)
    }

    private fun estimatePercentileDb(rmsDbList: FloatArray, count: Int, percentile: Double): Float {
        val valid = ArrayList<Float>(count)
        for (i in 0 until count) {
            val v = rmsDbList[i]
            if (v > -89f) valid.add(v)
        }
        if (valid.isEmpty()) return -96f
        valid.sort()
        val idx = ((valid.size - 1) * percentile).toInt().coerceIn(0, valid.lastIndex)
        return valid[idx]
    }

    private fun smoothArrayBidirectional(
        values: FloatArray,
        count: Int,
        alphaForward: Float,
        alphaBackward: Float
    ) {
        for (i in 1 until count) {
            values[i] = values[i - 1] + (values[i] - values[i - 1]) * alphaForward
        }
        for (i in count - 2 downTo 0) {
            values[i] = values[i + 1] + (values[i] - values[i + 1]) * alphaBackward
        }
    }

    private fun readFullChunk(input: BufferedInputStream, buf: ByteArray, targetBytes: Int): Int {
        var total = 0
        while (total < targetBytes) {
            val n = input.read(buf, total, targetBytes - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun readShortLE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return (hi shl 8) or lo
    }

    private fun linearToDb(x: Double): Double = 20.0 * log10(max(x, 1e-6))

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    private fun softLimit(x: Double): Double {
        val absX = abs(x)
        if (absX <= SOFT_KNEE_START) return x
        val excess = absX - SOFT_KNEE_START
        val headroom = SOFT_LIMIT_CEILING - SOFT_KNEE_START
        val compressed = SOFT_KNEE_START + headroom * tanh(excess / headroom)
        return if (x >= 0.0) compressed else -compressed
    }

    /**
     * 2nd-order Butterworth High-Pass (250 Hz) + 2nd-order Butterworth Low-Pass (3800 Hz)
     * isolating the cellular telephone speech band and removing rumble + high-frequency hiss.
     */
    private class SpeechBandpassFilter(sampleRate: Int) {
        private val sr = sampleRate.coerceAtLeast(8000).toDouble()
        private val hp = BiquadFilter.highPass(sr, HIGH_PASS_HZ, BUTTERWORTH_Q)
        private val lp = BiquadFilter.lowPass(sr, min(LOW_PASS_HZ, sr * 0.44), BUTTERWORTH_Q)

        fun process(x: Double): Double = lp.process(hp.process(x))
    }

    /**
     * Subtle +3 dB peaking filter centered at 2000 Hz (Q = 1.0) for consonant & medical-term clarity.
     */
    private class PresenceEqFilter(sampleRate: Int) {
        private val sr = sampleRate.coerceAtLeast(8000).toDouble()
        private val peak = BiquadFilter.peakingEq(sr, min(2000.0, sr * 0.35), 1.0, 3.0)

        fun process(x: Double): Double = peak.process(x)
    }

    private class BiquadFilter(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            fun highPass(sampleRate: Double, freqHz: Double, q: Double): BiquadFilter {
                val w0 = 2.0 * PI * freqHz / sampleRate
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 + cosW0) / 2.0) / a0,
                    b1 = -(1.0 + cosW0) / a0,
                    b2 = ((1.0 + cosW0) / 2.0) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }

            fun lowPass(sampleRate: Double, freqHz: Double, q: Double): BiquadFilter {
                val w0 = 2.0 * PI * freqHz / sampleRate
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = ((1.0 - cosW0) / 2.0) / a0,
                    b1 = (1.0 - cosW0) / a0,
                    b2 = ((1.0 - cosW0) / 2.0) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }

            fun peakingEq(sampleRate: Double, freqHz: Double, q: Double, gainDb: Double): BiquadFilter {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freqHz / sampleRate
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha / a
                return BiquadFilter(
                    b0 = (1.0 + alpha * a) / a0,
                    b1 = (-2.0 * cosW0) / a0,
                    b2 = (1.0 - alpha * a) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha / a) / a0
                )
            }
        }
    }

    companion object {
        private const val HIGH_PASS_HZ = 250.0
        private const val LOW_PASS_HZ = 3800.0
        private const val BUTTERWORTH_Q = 0.7071067811865476
        private const val TARGET_RMS_DB = -14.0
        private const val MIN_GAIN_DB = -6.0
        private const val NOISE_GATE_SNR_LOW_DB = 3.0
        private const val NOISE_GATE_SNR_HIGH_DB = 7.5
        private const val CEILING_LINEAR = 0.88
        private const val SOFT_KNEE_START = 0.82
        private const val SOFT_LIMIT_CEILING = 0.95
    }
}
