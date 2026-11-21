package com.tumorrecorder.app.service.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import com.tumorrecorder.app.domain.model.RecordingAudioSource
import com.tumorrecorder.app.domain.model.VoiceBoostLevel
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class LiveAudioTelemetry(
    val inputLevelNormalized: Float,
    val appliedGainDb: Float,
    val isSilencedWarning: Boolean
)

data class PcmRecorderStopSummary(
    val success: Boolean,
    val finalSource: RecordingAudioSource,
    val totalFrames: Int,
    val nonZeroFrames: Int,
    val maxRawPeak: Float,
    val avgAppliedGainDb: Float,
    val failureReason: String? = null
)

/**
 * Captures raw 16-bit mono PCM via [AudioRecord], disables software AEC/NS that can erase
 * faint earpiece speech, applies real-time lookahead DRC/AGC via [AudioGainProcessor],
 * and encodes directly to a standard AAC-LC `.m4a` file via [MediaCodec] + [MediaMuxer].
 *
 * Also monitors for Android's concurrent-capture zero-silencing (e.g. UNPROCESSED/CAMCORDER
 * silenced during calls on Samsung) and automatically falls back live to VOICE_RECOGNITION.
 */
class PcmAacRecorder(
    private val outputFile: File,
    private val preferredSource: RecordingAudioSource,
    private val boostLevel: VoiceBoostLevel,
    private val fromPhoneCall: Boolean,
    private val onSourceSwitched: (RecordingAudioSource, String?) -> Unit,
    private val onTelemetry: (LiveAudioTelemetry) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    @Volatile
    private var activeSource: RecordingAudioSource = preferredSource

    @Volatile
    private var stopSummary: PcmRecorderStopSummary = PcmRecorderStopSummary(
        success = false,
        finalSource = preferredSource,
        totalFrames = 0,
        nonZeroFrames = 0,
        maxRawPeak = 0f,
        avgAppliedGainDb = 0f,
        failureReason = "錄音執行緒未正常結束"
    )

    fun start(): RecordingAudioSource? {
        if (isRunning.get()) return activeSource

        val sampleRate = chooseSampleRate() ?: run {
            Log.e(TAG, "No supported AudioRecord sample rate found")
            return null
        }

        val orderedCandidates = if (fromPhoneCall) {
            listOf(
                preferredSource,
                RecordingAudioSource.VOICE_RECOGNITION,
                RecordingAudioSource.VOICE_COMMUNICATION,
                RecordingAudioSource.MIC
            ).distinct()
        } else {
            listOf(
                preferredSource,
                RecordingAudioSource.MIC,
                RecordingAudioSource.VOICE_RECOGNITION
            ).distinct()
        }

        val opened = openFirstWorkingAudioRecord(orderedCandidates, sampleRate) ?: return null
        val (initialRecord, initialSource, initialEffects) = opened
        activeSource = initialSource
        activeAudioRecord = initialRecord

        val codec: MediaCodec
        val muxer: MediaMuxer
        try {
            codec = createAacEncoder(sampleRate)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AAC MediaCodec/MediaMuxer", e)
            activeAudioRecord = null
            releaseAudioRecord(initialRecord, initialEffects)
            return null
        }

        isRunning.set(true)
        val thread = Thread(
            {
                runCaptureLoop(
                    sampleRate = sampleRate,
                    initialRecord = initialRecord,
                    initialSource = initialSource,
                    initialEffects = initialEffects,
                    codec = codec,
                    muxer = muxer
                )
            },
            "TumorRecorder-PCM-AAC"
        )
        workerThread = thread
        thread.start()
        return initialSource
    }

    fun stop(): PcmRecorderStopSummary {
        isRunning.set(false)
        // Immediately stop AudioRecord so any blocking read() unblocks even during Samsung call teardown
        activeAudioRecord?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            }
        }
        val thread = workerThread
        workerThread = null
        if (thread != null && thread.isAlive) {
            runCatching { thread.join(3500L) }
        }
        return stopSummary
    }

    private fun runCaptureLoop(
        sampleRate: Int,
        initialRecord: AudioRecord,
        initialSource: RecordingAudioSource,
        initialEffects: SessionEffects,
        codec: MediaCodec,
        muxer: MediaMuxer
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        var record = initialRecord
        var currentSource = initialSource
        var effects = initialEffects

        val frameSamples = (sampleRate / 50).coerceAtLeast(320) // 20 ms lookahead frame
        val pcmShorts = ShortArray(frameSamples)
        val pcmBytes = ByteArray(frameSamples * 2)
        val processor = AudioGainProcessor(sampleRate = sampleRate, boostLevel = boostLevel)
        val bufferInfo = MediaCodec.BufferInfo()

        var muxerTrackIndex = -1
        var muxerStarted = false
        var writtenPackets = 0
        var lastMuxerPtsUs = -1L
        var totalSamplesEncoded = 0L
        var loopError: String? = null

        var totalFrames = 0
        var nonZeroFrames = 0
        var consecutiveZeroFrames = 0
        var alreadySwitchedToVoiceRec = (currentSource == RecordingAudioSource.VOICE_RECOGNITION)
        var maxRawPeak = 0f
        var gainDbSum = 0.0
        var gainDbCount = 0
        var lastTelemetryMs = 0L

        try {
            while (isRunning.get()) {
                val readCount = record.read(pcmShorts, 0, pcmShorts.size)
                if (readCount <= 0) {
                    if (readCount < 0) {
                        Log.w(TAG, "AudioRecord.read returned error=$readCount")
                        Thread.sleep(10)
                    }
                    continue
                }

                totalFrames++
                val metrics = processor.processInPlace(pcmShorts, readCount)

                if (metrics.isAllZeros) {
                    consecutiveZeroFrames++
                    // If Android's concurrent capture policy silenced a non-VOICE_RECOGNITION source
                    // (e.g. UNPROCESSED or CAMCORDER during a Samsung call), switch live to VOICE_RECOGNITION!
                    if (fromPhoneCall &&
                        !alreadySwitchedToVoiceRec &&
                        consecutiveZeroFrames >= SILENCE_FALLBACK_FRAMES
                    ) {
                        alreadySwitchedToVoiceRec = true
                        Log.w(
                            TAG,
                            "Source ${currentSource.name} produced all-zero audio for ~1.2s during call; switching live to VOICE_RECOGNITION"
                        )
                        releaseAudioRecord(record, effects)
                        val fallback = tryOpenAudioRecord(RecordingAudioSource.VOICE_RECOGNITION, sampleRate)
                        if (fallback != null) {
                            record = fallback.first
                            activeAudioRecord = record
                            currentSource = RecordingAudioSource.VOICE_RECOGNITION
                            effects = fallback.second
                            activeSource = currentSource
                            consecutiveZeroFrames = 0
                            onSourceSwitched(
                                currentSource,
                                "原音源在通話中被系統靜音，已自動切回 VOICE_RECOGNITION"
                            )
                        } else {
                            // Re-open original if fallback failed
                            val retry = tryOpenAudioRecord(currentSource, sampleRate)
                            if (retry != null) {
                                record = retry.first
                                activeAudioRecord = record
                                effects = retry.second
                            } else {
                                break
                            }
                        }
                    }
                } else {
                    nonZeroFrames++
                    consecutiveZeroFrames = 0
                    if (metrics.rawPeakNormalized > maxRawPeak) {
                        maxRawPeak = metrics.rawPeakNormalized
                    }
                    gainDbSum += metrics.appliedGainDb
                    gainDbCount++
                }

                // Emit live level/boost telemetry ~5 times per second
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastTelemetryMs >= 200L) {
                    lastTelemetryMs = nowMs
                    val normalizedLevel = ((metrics.outputRmsDb + 55f) / 48f).coerceIn(0f, 1f)
                    val silencedWarn = consecutiveZeroFrames >= SILENCE_WARNING_FRAMES
                    onTelemetry(
                        LiveAudioTelemetry(
                            inputLevelNormalized = if (metrics.isAllZeros) 0f else normalizedLevel,
                            appliedGainDb = metrics.appliedGainDb.coerceAtLeast(0f),
                            isSilencedWarning = silencedWarn
                        )
                    )
                }

                // Convert little-endian 16-bit shorts to bytes
                var byteIdx = 0
                for (i in 0 until readCount) {
                    val sample = pcmShorts[i].toInt()
                    pcmBytes[byteIdx++] = (sample and 0xFF).toByte()
                    pcmBytes[byteIdx++] = ((sample shr 8) and 0xFF).toByte()
                }

                val totalBytes = readCount * 2
                var offset = 0
                while (offset < totalBytes && isRunning.get()) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputIndex)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            inputBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val chunk = minOf(inputBuf.remaining(), totalBytes - offset)
                            // Keep 2-byte PCM sample alignment
                            val alignedChunk = chunk - (chunk % 2)
                            if (alignedChunk > 0) {
                                inputBuf.put(pcmBytes, offset, alignedChunk)
                                val ptsUs = (totalSamplesEncoded * 1_000_000L) / sampleRate
                                codec.queueInputBuffer(inputIndex, 0, alignedChunk, ptsUs, 0)
                                totalSamplesEncoded += (alignedChunk / 2)
                                offset += alignedChunk
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                                break
                            }
                        }
                    }

                    val drainResult = drainEncoder(
                        codec = codec,
                        muxer = muxer,
                        bufferInfo = bufferInfo,
                        muxerTrackIndex = muxerTrackIndex,
                        muxerStarted = muxerStarted,
                        lastMuxerPtsUs = lastMuxerPtsUs,
                        endOfStream = false
                    )
                    muxerTrackIndex = drainResult.trackIndex
                    muxerStarted = drainResult.muxerStarted
                    lastMuxerPtsUs = drainResult.lastPtsUs
                    writtenPackets += drainResult.packetsWritten
                }
            }

            // Signal End-Of-Stream to MediaCodec and flush final AAC frames
            runCatching {
                var eosQueued = false
                for (attempt in 0 until 8) {
                    val preDrain = drainEncoder(
                        codec = codec,
                        muxer = muxer,
                        bufferInfo = bufferInfo,
                        muxerTrackIndex = muxerTrackIndex,
                        muxerStarted = muxerStarted,
                        lastMuxerPtsUs = lastMuxerPtsUs,
                        endOfStream = false
                    )
                    muxerTrackIndex = preDrain.trackIndex
                    muxerStarted = preDrain.muxerStarted
                    lastMuxerPtsUs = preDrain.lastPtsUs
                    writtenPackets += preDrain.packetsWritten

                    val inputIndex = codec.dequeueInputBuffer(30_000L)
                    if (inputIndex >= 0) {
                        val eosPtsUs = (totalSamplesEncoded * 1_000_000L) / sampleRate
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            eosPtsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        eosQueued = true
                        break
                    }
                }
                val drainResult = drainEncoder(
                    codec = codec,
                    muxer = muxer,
                    bufferInfo = bufferInfo,
                    muxerTrackIndex = muxerTrackIndex,
                    muxerStarted = muxerStarted,
                    lastMuxerPtsUs = lastMuxerPtsUs,
                    endOfStream = eosQueued
                )
                muxerTrackIndex = drainResult.trackIndex
                muxerStarted = drainResult.muxerStarted
                lastMuxerPtsUs = drainResult.lastPtsUs
                writtenPackets += drainResult.packetsWritten
            }.onFailure { e ->
                Log.w(TAG, "Error flushing AAC encoder EOS", e)
            }
        } catch (e: Exception) {
            loopError = e.message ?: e::class.java.simpleName
            Log.e(TAG, "Unexpected error in PCM-AAC recording loop", e)
        } finally {
            activeAudioRecord = null
            releaseAudioRecord(record, effects)
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }.onFailure {
                    loopError = loopError ?: "MediaMuxer.stop: ${it.message}"
                    Log.w(TAG, "MediaMuxer.stop failed", it)
                }
            }
            runCatching { muxer.release() }

            val avgGain = if (gainDbCount > 0) (gainDbSum / gainDbCount).toFloat() else 0f
            val fileBytes = if (outputFile.exists()) outputFile.length() else 0L
            val fileOk = muxerStarted && writtenPackets > 0 && fileBytes > 1024L
            val reason = if (fileOk) null else {
                loopError ?: "muxerStarted=$muxerStarted packets=$writtenPackets bytes=$fileBytes frames=$totalFrames"
            }
            stopSummary = PcmRecorderStopSummary(
                success = fileOk,
                finalSource = currentSource,
                totalFrames = totalFrames,
                nonZeroFrames = nonZeroFrames,
                maxRawPeak = maxRawPeak,
                avgAppliedGainDb = avgGain,
                failureReason = reason
            )
            Log.i(
                TAG,
                "Finished PcmAacRecorder: ok=$fileOk source=${currentSource.name} " +
                    "frames=$totalFrames nonZero=$nonZeroFrames rawPeak=${"%.3f".format(maxRawPeak)} " +
                    "avgGain=${"%.1f".format(avgGain)}dB fileBytes=$fileBytes reason=$reason"
            )
        }
    }

    private data class DrainResult(
        val trackIndex: Int,
        val muxerStarted: Boolean,
        val lastPtsUs: Long,
        val packetsWritten: Int
    )

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        muxerTrackIndex: Int,
        muxerStarted: Boolean,
        lastMuxerPtsUs: Long,
        endOfStream: Boolean
    ): DrainResult {
        var trackIndex = muxerTrackIndex
        var started = muxerStarted
        var lastPts = lastMuxerPtsUs
        var packets = 0
        val eosDeadlineMs = if (endOfStream) System.currentTimeMillis() + 1200L else 0L

        while (true) {
            val timeoutUs = if (endOfStream) 15_000L else 0L
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || System.currentTimeMillis() >= eosDeadlineMs) {
                        break
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                }

                outputIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputIndex)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && bufferInfo.size > 0 && started && encodedData != null) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        if (bufferInfo.presentationTimeUs <= lastPts) {
                            bufferInfo.presentationTimeUs = lastPts + 1L
                        }
                        lastPts = bufferInfo.presentationTimeUs
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        packets++
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }

                else -> break
            }
        }
        return DrainResult(trackIndex, started, lastPts, packets)
    }

    private fun createAacEncoder(sampleRate: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun chooseSampleRate(): Int? {
        for (rate in listOf(44_100, 16_000, 48_000)) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf > 0) return rate
        }
        return null
    }

    private data class SessionEffects(
        val aec: AcousticEchoCanceler?,
        val ns: NoiseSuppressor?
    )

    private fun openFirstWorkingAudioRecord(
        candidates: List<RecordingAudioSource>,
        sampleRate: Int
    ): Triple<AudioRecord, RecordingAudioSource, SessionEffects>? {
        for (candidate in candidates) {
            val opened = tryOpenAudioRecord(candidate, sampleRate)
            if (opened != null) {
                return Triple(opened.first, candidate, opened.second)
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun tryOpenAudioRecord(
        source: RecordingAudioSource,
        sampleRate: Int
    ): Pair<AudioRecord, SessionEffects>? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val bufferSize = max(minBuf * 4, sampleRate * 2 / 5) // >= 200ms buffer

        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source.toAndroidAudioSource(),
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }

            // Disable software Acoustic Echo Canceler and Noise Suppressor on this capture session
            // so Android does not actively erase the faint remote party voice leaking from the earpiece.
            val effects = disableSoftwareSuppression(record.audioSessionId)

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseAudioRecord(record, effects)
                return null
            }

            Log.i(
                TAG,
                "Started AudioRecord source=${source.name} sampleRate=$sampleRate sessionId=${record.audioSessionId} boost=${boostLevel.name}"
            )
            record to effects
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open AudioRecord for source=${source.name}", e)
            record?.let { runCatching { it.release() } }
            null
        }
    }

    private fun disableSoftwareSuppression(audioSessionId: Int): SessionEffects {
        val aec = runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = false
                }
            } else {
                null
            }
        }.getOrNull()

        val ns = runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = false
                }
            } else {
                null
            }
        }.getOrNull()

        return SessionEffects(aec = aec, ns = ns)
    }

    private fun releaseAudioRecord(record: AudioRecord, effects: SessionEffects) {
        runCatching { effects.aec?.release() }
        runCatching { effects.ns?.release() }
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        runCatching { record.release() }
    }

    companion object {
        private const val TAG = "TumorRecorder_PCM"
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val SILENCE_FALLBACK_FRAMES = 60 // ~1.2 seconds of all-zero PCM
        private const val SILENCE_WARNING_FRAMES = 125 // ~2.5 seconds of all-zero PCM

        fun RecordingAudioSource.toAndroidAudioSource(): Int = when (this) {
            RecordingAudioSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            RecordingAudioSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            RecordingAudioSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            RecordingAudioSource.MIC -> MediaRecorder.AudioSource.MIC
            RecordingAudioSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        }
    }
}
