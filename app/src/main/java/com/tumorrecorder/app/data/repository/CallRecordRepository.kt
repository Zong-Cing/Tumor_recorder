package com.tumorrecorder.app.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.tumorrecorder.app.data.local.CallRecordDao
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.local.SettingsRepository
import com.tumorrecorder.app.data.remote.EmailService
import com.tumorrecorder.app.domain.model.CallDirection
import com.tumorrecorder.app.domain.model.ProcessingStatus
import com.tumorrecorder.app.service.CallProcessingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CallRecordRepository(
    private val context: Context,
    private val dao: CallRecordDao,
    private val settingsRepository: SettingsRepository,
    private val emailService: EmailService
) {
    fun observeAllRecords(): Flow<List<CallRecordEntity>> = dao.observeAll()

    fun searchRecords(query: String): Flow<List<CallRecordEntity>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query.trim())

    fun observeRecord(id: Long): Flow<CallRecordEntity?> = dao.observeById(id)

    suspend fun getRecord(id: Long): CallRecordEntity? = dao.getById(id)

    suspend fun insertRecord(record: CallRecordEntity): Long = dao.insert(record)

    suspend fun updateRecord(record: CallRecordEntity) = dao.update(record)

    suspend fun deleteRecord(record: CallRecordEntity) = withContext(Dispatchers.IO) {
        if (record.audioFilePath.isNotBlank()) {
            runCatching { File(record.audioFilePath).delete() }
        }
        if (record.supplementAudioFilePath.isNotBlank()) {
            runCatching { File(record.supplementAudioFilePath).delete() }
        }
        dao.delete(record)
    }

    suspend fun saveSupplementAudio(recordId: Long, file: File, durationSeconds: Long) = withContext(Dispatchers.IO) {
        val existing = dao.getById(recordId) ?: return@withContext
        val oldPath = existing.supplementAudioFilePath
        if (oldPath.isNotBlank() && oldPath != file.absolutePath) {
            runCatching { File(oldPath).delete() }
        }
        dao.update(
            existing.copy(
                supplementAudioFilePath = file.absolutePath,
                supplementDurationSeconds = durationSeconds
            )
        )
    }

    suspend fun clearSupplementAudio(recordId: Long) = withContext(Dispatchers.IO) {
        val existing = dao.getById(recordId) ?: return@withContext
        if (existing.supplementAudioFilePath.isNotBlank()) {
            runCatching { File(existing.supplementAudioFilePath).delete() }
        }
        dao.update(
            existing.copy(
                supplementAudioFilePath = "",
                supplementDurationSeconds = 0L
            )
        )
    }

    /**
     * Imports an external audio file (e.g. built-in phone call recorder file) into private storage
     * and creates a CallRecordEntity ready for Gemini transcription and summarization.
     */
    suspend fun importAudioUri(uri: Uri, phoneNumber: String = ""): Long = withContext(Dispatchers.IO) {
        val recordingsDir = ensureRecordingsDir()
        val contentResolver = context.contentResolver
        val detectedMime = contentResolver.getType(uri) ?: guessMimeFromUri(uri)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(detectedMime)
            ?.takeIf { it.isNotBlank() }
            ?: "m4a"
        val destFile = File(recordingsDir, "imported_${System.currentTimeMillis()}.$ext")

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("無法讀取選擇的音檔")

        val durationSeconds = extractAudioDurationSeconds(destFile)
        val entity = CallRecordEntity(
            phoneNumber = phoneNumber,
            direction = CallDirection.IMPORTED.name,
            startedAtMillis = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            audioFilePath = destFile.absolutePath,
            audioMimeType = normalizeAudioMimeType(detectedMime),
            status = ProcessingStatus.PENDING.name
        )
        val id = dao.insert(entity)
        if (settingsRepository.currentSettings().autoAnalyzeAfterCall) {
            enqueueProcessing(id)
        }
        id
    }

    fun enqueueProcessing(
        recordId: Long,
        promptTemplateId: Long = -1L,
        forceSendEmail: Boolean = false
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<CallProcessingWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    CallProcessingWorker.KEY_RECORD_ID to recordId,
                    CallProcessingWorker.KEY_PROMPT_TEMPLATE_ID to promptTemplateId,
                    CallProcessingWorker.KEY_FORCE_EMAIL to forceSendEmail
                )
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "process_call_$recordId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun resendEmailOnly(recordId: Long) {
        val record = dao.getById(recordId) ?: error("找不到通話紀錄")
        val settings = settingsRepository.currentSettings()
        dao.update(
            record.copy(
                status = ProcessingStatus.SENDING_EMAIL.name,
                errorMessage = null
            )
        )
        try {
            emailService.sendCallSummaryEmail(record, settings)
            dao.update(
                record.copy(
                    status = ProcessingStatus.COMPLETED.name,
                    emailSent = true,
                    emailSentAtMillis = System.currentTimeMillis(),
                    emailRecipientSnapshot = settings.parsedRecipients().joinToString(", "),
                    errorMessage = null
                )
            )
        } catch (e: Exception) {
            dao.update(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "寄信失敗：${e.message ?: "未知錯誤"}"
                )
            )
            throw e
        }
    }

    fun ensureRecordingsDir(): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun extractAudioDurationSeconds(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (ms / 1000L).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun guessMimeFromUri(uri: Uri): String {
        val path = uri.toString().lowercase()
        return when {
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".ogg") -> "audio/ogg"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".amr") -> "audio/amr"
            path.endsWith(".3gp") -> "audio/3gpp"
            else -> "audio/mp4"
        }
    }

    private fun normalizeAudioMimeType(mime: String): String {
        return when (mime.lowercase()) {
            "audio/x-m4a", "audio/mp4a-latm" -> "audio/mp4"
            "application/octet-stream" -> "audio/mp4"
            else -> mime
        }
    }
}
