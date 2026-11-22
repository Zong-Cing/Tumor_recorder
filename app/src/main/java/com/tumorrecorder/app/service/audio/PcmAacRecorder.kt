package com.tumorrecorder.app.service.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import com.tumorrecorder.app.domain.model.RecordingAudioSource
import com.tumorrecorder.app.domain.model.VoiceBoostLevel
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class LiveAudioTelemetry(
    val inputLevelNormalized: Float,
    val appliedGainDb: Float,
    val isSilencedWarning: Boolean,
    val isDualMic: Boolean = false
)

data class PcmRecorderStopSummary(
    val success: Boolean,
    val finalSource: RecordingAudioSource,
    val finalFile: File,
    val finalMimeType: String,
    val totalFrames: Int,
    val nonZeroFrames: Int,
    val maxRawPeak: Float,
    val masteringReport: MasteringReport? = null,
    val failureReason: String? = null
)

/**
 * Two-stage dual-mic call recorder:
 *
 * Stage 1 (Live Call):
 * - Captures raw 16-bit PCM via [AudioRecord] using STEREO when available (on Samsung Galaxy,
 *   captures both the bottom mic and the top mic located right next to the earpiece), falling
 *   back cleanly to MONO if stereo is unavailable.
 * - Preserves Samsung's native AEC & enables [NoiseSuppressor] when available so background
 *   hiss is never introduced.
 * - Streams raw PCM to a temporary file with live all-zero silence detection (auto-falls back
 *   to VOICE_RECOGNITION if Android silences another source during a call).
 *
 * Stage 2 (Post-Call ~0.2–0.6s):
 * - Runs [SpeechMasteringProcessor] 2-pass bandpass (250–3800 Hz) + adaptive dual-mic SNR
 *   blending + empirical noise-floor suppression + zero-lag lookahead speech leveling.
 * - Encodes mastered mono speech to AAC-LC `.m4a` via [MediaCodec] + [MediaMuxer], with an
 *   automatic `.wav` fallback so no recording is ever lost due to a codec quirk.
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
        finalFile = outputFile,
        finalMimeType = "audio/mp4",
        totalFrames = 0,
        nonZeroFrames = 0,
        maxRawPeak = 0f,
        failureReason = "錄音執行緒未正常結束"
    )

    private var captureSampleRate: Int = 16_000
    private var captureChannelCount: Int = 1
    private val rawPcmFile: File = File(outputFile.parentFile, "${outputFile.name}.raw.pcm")

    fun start(): RecordingAudioSource? {
        if (isRunning.get()) return activeSource

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

        val opened = openBestAudioRecord(orderedCandidates) ?: return null
        activeSource = opened.source
        activeAudioRecord = opened.record
        captureSampleRate = opened.sampleRate
        captureChannelCount = opened.channelCount

        isRunning.set(true)
        val thread = Thread(
            { runCaptureAndMasterLoop(opened) },
            "TumorRecorder-DualMic-PCM"
        )
        workerThread = thread
        thread.start()
        return opened.source
    }

    fun stop(): PcmRecorderStopSummary {
        isRunning.set(false)
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
            // Allow enough time for 2-pass post-call mastering + AAC encoding
            runCatching { thread.join(12_000L) }
        }
        return stopSummary
    }

    private fun runCaptureAndMasterLoop(initial: OpenedAudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        var record = initial.record
        var currentSource = initial.source
        var nsEffect = initial.noiseSuppressor
        val sampleRate = initial.sampleRate
        val channelCount = initial.channelCount

        val frameSamplesPerChannel = (sampleRate / 50).coerceAtLeast(320) // 20 ms
        val readShorts = ShortArray(frameSamplesPerChannel * channelCount)
        val writeBytes = ByteArray(readShorts.size * 2)

        var totalFrames = 0
        var nonZeroFrames = 0
        var consecutiveZeroFrames = 0
        var alreadySwitchedToVoiceRec = (currentSource == RecordingAudioSource.VOICE_RECOGNITION)
        var maxRawPeak = 0f
        var lastTelemetryMs = 0L
        var captureError: String? = null

        try {
            BufferedOutputStream(FileOutputStream(rawPcmFile), 65_536).use { rawOut ->
                while (isRunning.get()) {
                    val readCount = record.read(readShorts, 0, readShorts.size)
                    if (readCount <= 0) {
                        if (readCount < 0) {
                            Log.w(TAG, "AudioRecord.read returned error=$readCount")
                            Thread.sleep(10)
                        }
                        continue
                    }

                    totalFrames++
                    var anyNonZero = false
                    var framePeak = 0.0
                    var sumSq = 0.0

                    var byteIdx = 0
                    for (i in 0 until readCount) {
                        val s = readShorts[i].toInt()
                        if (s != 0) anyNonZero = true
                        val norm = s / 32768.0
                        val a = abs(norm)
                        if (a > framePeak) framePeak = a
                        sumSq += norm * norm

                        writeBytes[byteIdx++] = (s and 0xFF).toByte()
                        writeBytes[byteIdx++] = ((s shr 8) and 0xFF).toByte()
                    }

                    if (!anyNonZero) {
                        consecutiveZeroFrames++
                        if (fromPhoneCall &&
                            !alreadySwitchedToVoiceRec &&
                            consecutiveZeroFrames >= SILENCE_FALLBACK_FRAMES
                        ) {
                            alreadySwitchedToVoiceRec = true
                            Log.w(
                                TAG,
                                "Source ${currentSource.name} produced all-zero PCM for ~1.2s; switching live to VOICE_RECOGNITION"
                            )
                            releaseAudioRecord(record, nsEffect)
                            val fallback = tryOpenAudioRecord(
                                source = RecordingAudioSource.VOICE_RECOGNITION,
                                sampleRate = sampleRate,
                                channelCount = channelCount
                            )
                            if (fallback != null) {
                                record = fallback.record
                                activeAudioRecord = record
                                currentSource = RecordingAudioSource.VOICE_RECOGNITION
                                nsEffect = fallback.noiseSuppressor
                                activeSource = currentSource
                                consecutiveZeroFrames = 0
                                onSourceSwitched(
                                    currentSource,
                                    "原音源在通話中被系統靜音，已自動切回 VOICE_RECOGNITION"
                                )
                            } else {
                                val retry = tryOpenAudioRecord(currentSource, sampleRate, channelCount)
                                if (retry != null) {
                                    record = retry.record
                                    activeAudioRecord = record
                                    nsEffect = retry.noiseSuppressor
                                } else {
                                    break
                                }
                            }
                        }
                    } else {
                        nonZeroFrames++
                        consecutiveZeroFrames = 0
                        if (framePeak.toFloat() > maxRawPeak) {
                            maxRawPeak = framePeak.toFloat()
                        }
                    }

                    // Write complete aligned frames (channelCount * 2 bytes per sample frame)
                    val alignedBytes = (readCount - (readCount % channelCount)) * 2
                    if (alignedBytes > 0) {
                        rawOut.write(writeBytes, 0, alignedBytes)
                    }

                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastTelemetryMs >= 200L) {
                        lastTelemetryMs = nowMs
                        val rms = sqrt(sumSq / readCount)
                        val rmsDb = (20.0 * log10(max(rms, 1e-6))).toFloat()
                        val level = ((rmsDb + 60f) / 52f).coerceIn(0f, 1f)
                        onTelemetry(
                            LiveAudioTelemetry(
                                inputLevelNormalized = if (!anyNonZero) 0f else level,
                                appliedGainDb = 0f,
                                isSilencedWarning = consecutiveZeroFrames >= SILENCE_WARNING_FRAMES,
                                isDualMic = channelCount == 2
                            )
                        )
                    }
                }
                rawOut.flush()
            }
        } catch (e: Exception) {
            captureError = e.message ?: e::class.java.simpleName
            Log.e(TAG, "Error during PCM capture", e)
        } finally {
            activeAudioRecord = null
            releaseAudioRecord(record, nsEffect)
        }

        // Stage 2: 2-pass post-call speech mastering + AAC (.m4a) encoding (with WAV fallback)
        val masterStartMs = System.currentTimeMillis()
        var finalFile = outputFile
        var finalMime = "audio/mp4"
        var masteringReport: MasteringReport? = null
        var finalOk = false
        var finalReason: String? = captureError

        try {
            if (!rawPcmFile.exists() || rawPcmFile.length() < sampleRate / 4L) {
                finalReason = finalReason ?: "錄音過短 (${rawPcmFile.length()} raw bytes)"
            } else {
                val processor = SpeechMasteringProcessor(
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    boostLevel = boostLevel
                )

                // Try AAC-LC (.m4a) encoding first
                val aacResult = encodeMasteredToM4a(processor, rawPcmFile, outputFile, sampleRate)
                if (aacResult != null && outputFile.exists() && outputFile.length() > 1024L) {
                    masteringReport = aacResult
                    finalFile = outputFile
                    finalMime = "audio/mp4"
                    finalOk = true
                    finalReason = null
                } else {
                    // Fallback: write lossless 16-bit WAV so no call recording is ever lost
                    val wavFile = File(
                        outputFile.parentFile,
                        outputFile.nameWithoutExtension + ".wav"
                    )
                    val wavReport = encodeMasteredToWav(processor, rawPcmFile, wavFile, sampleRate)
                    if (wavReport != null && wavFile.exists() && wavFile.length() > 1024L) {
                        runCatching { outputFile.delete() }
                        masteringReport = wavReport
                        finalFile = wavFile
                        finalMime = "audio/wav"
                        finalOk = true
                        finalReason = null
                    } else {
                        finalReason = finalReason ?: "AAC 與 WAV 編碼皆失敗"
                    }
                }
            }
        } catch (e: Exception) {
            finalReason = e.message ?: e::class.java.simpleName
            Log.e(TAG, "Post-call mastering/encoding failed", e)
        } finally {
            runCatching { rawPcmFile.delete() }
            val masterMs = System.currentTimeMillis() - masterStartMs
            stopSummary = PcmRecorderStopSummary(
                success = finalOk,
                finalSource = currentSource,
                finalFile = finalFile,
                finalMimeType = finalMime,
                totalFrames = totalFrames,
                nonZeroFrames = nonZeroFrames,
                maxRawPeak = maxRawPeak,
                masteringReport = masteringReport,
                failureReason = finalReason
            )
            Log.i(
                TAG,
                "Finished TwoPassRecorder: ok=$finalOk mime=$finalMime bytes=${if (finalFile.exists()) finalFile.length() else 0} " +
                    "source=${currentSource.name} sr=$sampleRate ch=$channelCount " +
                    "dualMicDistinct=${masteringReport?.dualMicDistinct} " +
                    "noiseFloor=${masteringReport?.noiseFloorDb?.let { "%.1f".format(it) }}dB " +
                    "speechPeak=${masteringReport?.speechPeakDb?.let { "%.1f".format(it) }}dB " +
                    "maxGain=${masteringReport?.maxAppliedGainDb?.let { "%.1f".format(it) }}dB " +
                    "masterMs=${masterMs}ms reason=$finalReason"
            )
        }
    }

    private fun encodeMasteredToM4a(
        processor: SpeechMasteringProcessor,
        pcmFile: File,
        m4aFile: File,
        sampleRate: Int
    ): MasteringReport? {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerTrackIndex = -1
        var writtenPackets = 0
        var lastPtsUs = -1L
        var totalSamplesEncoded = 0L
        val bufferInfo = MediaCodec.BufferInfo()

        return try {
            codec = createAacEncoder(sampleRate)
            muxer = MediaMuxer(m4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val enc = codec
            val mux = muxer
            var pcmBytes = ByteArray(0)

            val report = processor.processPcmFile(pcmFile) { shorts, count ->
                val totalBytes = count * 2
                if (pcmBytes.size < totalBytes) {
                    pcmBytes = ByteArray(totalBytes)
                }
                var bIdx = 0
                for (i in 0 until count) {
                    val s = shorts[i].toInt()
                    pcmBytes[bIdx++] = (s and 0xFF).toByte()
                    pcmBytes[bIdx++] = ((s shr 8) and 0xFF).toByte()
                }

                var offset = 0
                var guard = 0
                while (offset < totalBytes && guard < 200) {
                    guard++
                    val inputIndex = enc.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuf = enc.getInputBuffer(inputIndex)
                        if (inputBuf != null) {
                            inputBuf.clear()
                            inputBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val chunk = minOf(inputBuf.remaining(), totalBytes - offset)
                            val aligned = chunk - (chunk % 2)
                            if (aligned > 0) {
                                inputBuf.put(pcmBytes, offset, aligned)
                                val ptsUs = (totalSamplesEncoded * 1_000_000L) / sampleRate
                                enc.queueInputBuffer(inputIndex, 0, aligned, ptsUs, 0)
                                totalSamplesEncoded += (aligned / 2)
                                offset += aligned
                            } else {
                                enc.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                                break
                            }
                        }
                    }

                    val drain = drainEncoder(
                        codec = enc,
                        muxer = mux,
                        bufferInfo = bufferInfo,
                        muxerTrackIndex = muxerTrackIndex,
                        muxerStarted = muxerStarted,
                        lastMuxerPtsUs = lastPtsUs,
                        endOfStream = false
                    )
                    muxerTrackIndex = drain.trackIndex
                    muxerStarted = drain.muxerStarted
                    lastPtsUs = drain.lastPtsUs
                    writtenPackets += drain.packetsWritten
                }
            }

            // Signal End-Of-Stream and drain remaining AAC packets
            var eosQueued = false
            for (attempt in 0 until 12) {
                val preDrain = drainEncoder(enc, mux, bufferInfo, muxerTrackIndex, muxerStarted, lastPtsUs, false)
                muxerTrackIndex = preDrain.trackIndex
                muxerStarted = preDrain.muxerStarted
                lastPtsUs = preDrain.lastPtsUs
                writtenPackets += preDrain.packetsWritten

                val inputIndex = enc.dequeueInputBuffer(30_000L)
                if (inputIndex >= 0) {
                    val eosPtsUs = (totalSamplesEncoded * 1_000_000L) / sampleRate
                    enc.queueInputBuffer(inputIndex, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                    break
                }
            }
            val finalDrain = drainEncoder(enc, mux, bufferInfo, muxerTrackIndex, muxerStarted, lastPtsUs, eosQueued)
            muxerTrackIndex = finalDrain.trackIndex
            muxerStarted = finalDrain.muxerStarted
            lastPtsUs = finalDrain.lastPtsUs
            writtenPackets += finalDrain.packetsWritten

            if (muxerStarted && writtenPackets > 0) report else null
        } catch (e: Exception) {
            Log.w(TAG, "AAC M4A encoding failed; will try WAV fallback", e)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }.onFailure { Log.w(TAG, "MediaMuxer.stop failed", it) }
            }
            runCatching { muxer?.release() }
        }
    }

    private fun encodeMasteredToWav(
        processor: SpeechMasteringProcessor,
        pcmFile: File,
        wavFile: File,
        sampleRate: Int
    ): MasteringReport? {
        return try {
            var totalDataBytes = 0L
            var chunkBytes = ByteArray(0)
            BufferedOutputStream(FileOutputStream(wavFile), 65_536).use { out ->
                // Placeholder 44-byte RIFF/WAV header
                out.write(ByteArray(44))
                val report = processor.processPcmFile(pcmFile) { shorts, count ->
                    val len = count * 2
                    if (chunkBytes.size < len) chunkBytes = ByteArray(len)
                    var bIdx = 0
                    for (i in 0 until count) {
                        val s = shorts[i].toInt()
                        chunkBytes[bIdx++] = (s and 0xFF).toByte()
                        chunkBytes[bIdx++] = ((s shr 8) and 0xFF).toByte()
                    }
                    out.write(chunkBytes, 0, len)
                    totalDataBytes += len
                }
                out.flush()
                writeWavHeader(wavFile, sampleRate, 1, totalDataBytes)
                report
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV fallback encoding failed", e)
            null
        }
    }

    private fun writeWavHeader(wavFile: File, sampleRate: Int, channels: Int, dataBytes: Long) {
        val byteRate = sampleRate * channels * 2
        val totalSizeMinus8 = (36L + dataBytes).toInt()
        val dataSize = dataBytes.toInt()
        val header = ByteArray(44)
        // RIFF
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        putIntLE(header, 4, totalSizeMinus8)
        // WAVE
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        putIntLE(header, 16, 16)
        putShortLE(header, 20, 1) // PCM
        putShortLE(header, 22, channels)
        putIntLE(header, 24, sampleRate)
        putIntLE(header, 28, byteRate)
        putShortLE(header, 32, channels * 2)
        putShortLE(header, 34, 16)
        // data
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        putIntLE(header, 40, dataSize)

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(0L)
            raf.write(header)
        }
    }

    private fun putIntLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putShortLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
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
        val eosDeadlineMs = if (endOfStream) System.currentTimeMillis() + 1500L else 0L

        while (true) {
            val timeoutUs = if (endOfStream) 15_000L else 0L
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || System.currentTimeMillis() >= eosDeadlineMs) break
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
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                else -> break
            }
        }
        return DrainResult(trackIndex, started, lastPts, packets)
    }

    private fun createAacEncoder(sampleRate: Int): MediaCodec {
        val bitRate = if (sampleRate <= 16_000) 64_000 else 96_000
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private data class OpenedAudioRecord(
        val record: AudioRecord,
        val source: RecordingAudioSource,
        val sampleRate: Int,
        val channelCount: Int,
        val noiseSuppressor: NoiseSuppressor?
    )

    private fun openBestAudioRecord(candidates: List<RecordingAudioSource>): OpenedAudioRecord? {
        // Prefer 16 kHz wideband HD voice (fast 2-pass mastering + ideal for speech/Whisper), fallback 44.1 kHz
        val sampleRates = listOf(16_000, 44_100, 48_000)
        // Prefer STEREO (2 channels: bottom mic + top mic near earpiece on Samsung), fallback MONO
        val channelCounts = listOf(2, 1)

        for (candidate in candidates) {
            for (sr in sampleRates) {
                for (ch in channelCounts) {
                    val opened = tryOpenAudioRecord(candidate, sr, ch)
                    if (opened != null) return opened
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun tryOpenAudioRecord(
        source: RecordingAudioSource,
        sampleRate: Int,
        channelCount: Int
    ): OpenedAudioRecord? {
        val channelConfig = if (channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val bufferSize = max(minBuf * 4, sampleRate * channelCount * 2 / 5) // >= 200 ms

        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source.toAndroidAudioSource(),
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }

            // Preserve Samsung's native AEC (do NOT disable it!) and enable NoiseSuppressor
            // when available so background static/hiss stays clean.
            val ns = runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(record.audioSessionId)?.apply {
                        enabled = true
                    }
                } else {
                    null
                }
            }.getOrNull()

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseAudioRecord(record, ns)
                return null
            }

            Log.i(
                TAG,
                "Started AudioRecord source=${source.name} sr=$sampleRate ch=$channelCount " +
                    "nsEnabled=${ns?.enabled} sessionId=${record.audioSessionId} boost=${boostLevel.name}"
            )
            OpenedAudioRecord(
                record = record,
                source = source,
                sampleRate = sampleRate,
                channelCount = channelCount,
                noiseSuppressor = ns
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open AudioRecord source=${source.name} sr=$sampleRate ch=$channelCount", e)
            record?.let { runCatching { it.release() } }
            null
        }
    }

    private fun releaseAudioRecord(record: AudioRecord, ns: NoiseSuppressor?) {
        runCatching { ns?.release() }
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
