package com.tumorrecorder.app

import com.tumorrecorder.app.domain.model.VoiceBoostLevel
import com.tumorrecorder.app.service.audio.SpeechMasteringProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechMasteringProcessorTest {

    private val sampleRate = 16_000
    private val frameSamples = sampleRate / 50 // 320 samples = 20 ms

    private fun writePcmTempFile(samples: ShortArray): File {
        val f = File.createTempFile("tumor_master_test_", ".pcm")
        f.deleteOnExit()
        FileOutputStream(f).use { out ->
            val bytes = ByteArray(samples.size * 2)
            var idx = 0
            for (s in samples) {
                val v = s.toInt()
                bytes[idx++] = (v and 0xFF).toByte()
                bytes[idx++] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
        return f
    }

    private fun rmsRange(samples: ShortArray, start: Int, end: Int): Double {
        var sumSq = 0.0
        val count = (end - start).coerceAtLeast(1)
        for (i in start until end) {
            val n = samples[i] / 32768.0
            sumSq += n * n
        }
        return sqrt(sumSq / count)
    }

    @Test
    fun twoPassMasteringBoostsQuietRemoteVoiceWhileSuppressingNoiseFloorAndLimitingLoudLocal() {
        // Build a 3-second mono recording at 16 kHz:
        // 0.0s - 1.0s: low background hiss (-62 dBFS, 1000 Hz proxy)
        // 1.0s - 2.0s: faint remote voice (-46 dBFS, 1000 Hz)
        // 2.0s - 3.0s: loud local voice (-2 dBFS, 1000 Hz)
        val total = sampleRate * 3
        val input = ShortArray(total) { i ->
            val amp = when {
                i < sampleRate -> 0.0011       // ~-62 dBFS noise floor
                i < sampleRate * 2 -> 0.0075   // ~-46 dBFS faint remote caller
                else -> 0.80                   // ~-2 dBFS loud local caller
            }
            (sin(2.0 * PI * 1000.0 * i / sampleRate) * amp * 32767.0).toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }

        val pcmFile = writePcmTempFile(input)
        val outList = ArrayList<Short>(total)
        val processor = SpeechMasteringProcessor(sampleRate, channelCount = 1, boostLevel = VoiceBoostLevel.STRONG)
        val report = processor.processPcmFile(pcmFile) { chunk, count ->
            for (i in 0 until count) outList.add(chunk[i])
        }
        val output = outList.toShortArray()

        val rawRemoteRms = rmsRange(input, sampleRate + 3200, sampleRate * 2 - 3200)
        val outNoiseRms = rmsRange(output, 3200, sampleRate - 3200)
        val outRemoteRms = rmsRange(output, sampleRate + 3200, sampleRate * 2 - 3200)
        val outLocalMaxAbs = (sampleRate * 2 until output.size).maxOf { abs(output[it].toInt()) }

        assertTrue("Expected >= 10x RMS boost on quiet remote voice, got ${"%.2f".format(outRemoteRms / rawRemoteRms)}x", outRemoteRms / rawRemoteRms >= 10.0)
        assertTrue("Expected noise floor pause to stay much quieter than boosted remote speech", outNoiseRms < outRemoteRms * 0.15)
        assertTrue("Expected loud local speech peak <= 32500 without clipping, got $outLocalMaxAbs", outLocalMaxAbs <= 32500)
        assertTrue("Expected maxAppliedGainDb >= 20 dB, got ${report.maxAppliedGainDb}", report.maxAppliedGainDb >= 20f)
    }

    @Test
    fun stereoDualMicSelectsCleanerTopMicWhenBottomMicHasOnlyNoise() {
        // 2-second stereo capture (interleaved L, R):
        // L (bottom mic): faint background noise (-62 dBFS) throughout
        // R (top mic near earpiece): clear remote voice (-34 dBFS) in second half
        val framesPerChannel = sampleRate * 2
        val interleaved = ShortArray(framesPerChannel * 2)
        for (i in 0 until framesPerChannel) {
            val lVal = sin(2.0 * PI * 900.0 * i / sampleRate) * 0.0011
            val rAmp = if (i < sampleRate) 0.0011 else 0.028
            val rVal = sin(2.0 * PI * 1200.0 * i / sampleRate) * rAmp
            interleaved[i * 2] = (lVal * 32767.0).toInt().toShort()
            interleaved[i * 2 + 1] = (rVal * 32767.0).toInt().toShort()
        }

        val pcmFile = writePcmTempFile(interleaved)
        val outList = ArrayList<Short>(framesPerChannel)
        val processor = SpeechMasteringProcessor(sampleRate, channelCount = 2, boostLevel = VoiceBoostLevel.STANDARD)
        val report = processor.processPcmFile(pcmFile) { chunk, count ->
            for (i in 0 until count) outList.add(chunk[i])
        }
        val output = outList.toShortArray()

        val firstHalfRms = rmsRange(output, 3200, sampleRate - 3200)
        val secondHalfRms = rmsRange(output, sampleRate + 3200, framesPerChannel - 3200)

        assertTrue("Expected dualMicDistinct = true for distinct L/R channels", report.dualMicDistinct)
        assertTrue(
            "Expected top mic remote speech to stand clearly above noise floor (ratio=${"%.2f".format(secondHalfRms / firstHalfRms.coerceAtLeast(1e-6))})",
            secondHalfRms > firstHalfRms * 10.0
        )
    }

    @Test
    fun bandpassFilterAttenuatesLowRumbleAndHighHiss() {
        fun filteredPeakDb(freqHz: Double): Float {
            val samples = ShortArray(sampleRate) { i ->
                (sin(2.0 * PI * freqHz * i / sampleRate) * 0.2 * 32767.0).toInt().toShort()
            }
            val pcmFile = writePcmTempFile(samples)
            val processor = SpeechMasteringProcessor(sampleRate, channelCount = 1, boostLevel = VoiceBoostLevel.STANDARD)
            val report = processor.processPcmFile(pcmFile) { _, _ -> }
            return report.speechPeakDb
        }

        val speechDb = filteredPeakDb(1000.0)
        val rumbleDb = filteredPeakDb(65.0)
        val hissDb = filteredPeakDb(7200.0)

        assertTrue(
            "Expected >= 15 dB rejection of 65 Hz rumble (speech=${"%.1f".format(speechDb)} dB, rumble=${"%.1f".format(rumbleDb)} dB)",
            speechDb - rumbleDb >= 15f
        )
        assertTrue(
            "Expected >= 15 dB rejection of 7200 Hz hiss (speech=${"%.1f".format(speechDb)} dB, hiss=${"%.1f".format(hissDb)} dB)",
            speechDb - hissDb >= 15f
        )
    }
}
