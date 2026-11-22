package com.tumorrecorder.app.service.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Simple, reliable native MediaRecorder wrapper for recording the case manager's
 * post-call voice supplement memo after hanging up.
 */
class VoiceMemoRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMillis: Long = 0L

    val isRecording: Boolean
        get() = recorder != null

    fun start(targetFile: File): Boolean {
        if (recorder != null) return false
        val mr = createMediaRecorder()
        return try {
            targetFile.parentFile?.mkdirs()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioChannels(1)
            mr.setOutputFile(targetFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = targetFile
            startedAtMillis = System.currentTimeMillis()
            Log.i(TAG, "Started voice memo recording -> ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice memo recording", e)
            runCatching { mr.reset() }
            runCatching { mr.release() }
            runCatching { targetFile.delete() }
            false
        }
    }

    /**
     * Stops recording and returns Pair(file, durationSeconds) if valid, or null on error/too short.
     */
    fun stop(): Pair<File, Long>? {
        val mr = recorder ?: return null
        val file = outputFile
        val elapsedSec = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(1L)
        recorder = null
        outputFile = null

        val ok = runCatching {
            mr.stop()
            true
        }.getOrElse { e ->
            Log.w(TAG, "VoiceMemoRecorder stop failed", e)
            false
        }
        runCatching { mr.reset() }
        runCatching { mr.release() }

        return if (ok && file != null && file.exists() && file.length() > 1024L) {
            Log.i(TAG, "Saved voice memo: ${file.name} (${elapsedSec}s, ${file.length()} bytes)")
            file to elapsedSec
        } else {
            file?.let { runCatching { it.delete() } }
            null
        }
    }

    fun cancelAndRelease() {
        val mr = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.reset() }
            runCatching { mr.release() }
        }
        file?.let { runCatching { it.delete() } }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }

    companion object {
        private const val TAG = "TumorRecorder_Memo"
    }
}
