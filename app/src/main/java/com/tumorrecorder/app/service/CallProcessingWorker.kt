package com.tumorrecorder.app.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tumorrecorder.app.MainActivity
import com.tumorrecorder.app.R
import com.tumorrecorder.app.TumorRecorderApp
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.ProcessingStatus
import java.io.File

/**
 * Reliable background worker that runs:
 *   1. Gemini audio upload + structured oncology transcription & summary
 *   2. Persisting structured fields & full transcript to Room DB
 *   3. Automatic Gmail SMTP delivery (if enabled & configured)
 */
class CallProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val forceEmail = inputData.getBoolean(KEY_FORCE_EMAIL, false)
        if (recordId <= 0L) return Result.failure()

        val app = applicationContext as TumorRecorderApp
        val repo = app.container.callRecordRepository
        val settings = app.container.settingsRepository.currentSettings()
        val geminiService = app.container.geminiService
        val emailService = app.container.emailService

        var record = repo.getRecord(recordId) ?: return Result.failure()

        if (!settings.isGeminiConfigured) {
            repo.updateRecord(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "尚未設定 Gemini API Key，請先至「設定」填寫後再點擊重新分析。"
                )
            )
            notifyUser(recordId, "無法進行 AI 摘要", "請先至設定頁填入 Gemini API Key。")
            return Result.failure()
        }

        val audioFile = File(record.audioFilePath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            repo.updateRecord(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "找不到錄音檔案，可能已被移除。"
                )
            )
            return Result.failure()
        }

        // Step 1: Transcribe & Summarize via Gemini
        record = record.copy(
            status = ProcessingStatus.TRANSCRIBING.name,
            errorMessage = null
        )
        repo.updateRecord(record)

        val summaryResult = try {
            geminiService.transcribeAndSummarize(
                audioFile = audioFile,
                mimeType = record.audioMimeType.ifBlank { "audio/mp4" },
                phoneNumber = record.phoneNumber,
                callTimeDisplay = EmailFormatter.formatTimestamp(record.startedAtMillis),
                settings = settings
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gemini processing failed for record $recordId", e)
            repo.updateRecord(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "AI 分析失敗：${e.message ?: "請檢查網路或 API Key"}"
                )
            )
            notifyUser(recordId, "通話 AI 摘要失敗", e.message ?: "請開啟 App 檢視並重試。")
            return Result.failure()
        }

        // Step 2: Persist extracted oncology case manager details
        record = record.copy(
            patientName = summaryResult.patientName.ifBlank { "未提及" },
            patientIdentifier = summaryResult.patientIdentifier.ifBlank { "未提及" },
            identifierType = summaryResult.identifierType.ifBlank { "未提及" },
            caseManagerName = summaryResult.caseManagerName.ifBlank { "未提及" },
            callerRelation = summaryResult.callerRelation.ifBlank { "未提及" },
            inquiryCategory = summaryResult.inquiryCategory.ifBlank { "一般諮詢" },
            urgencyLevel = summaryResult.urgencyLevel.ifBlank { "一般" },
            summary = summaryResult.summary,
            actionItemsText = summaryResult.actionItems.joinToString("\n"),
            transcript = summaryResult.transcript,
            errorMessage = null
        )

        val shouldEmail = (settings.autoSendEmailAfterSummary || forceEmail) && settings.isEmailConfigured
        if (!shouldEmail) {
            val saved = record.copy(status = ProcessingStatus.COMPLETED_NO_EMAIL.name)
            repo.updateRecord(saved)
            notifyUser(
                recordId,
                "通話摘要已完成：${saved.patientName}",
                if (!settings.isEmailConfigured) "摘要與逐字稿已儲存（尚未設定 Gmail SMTP）" else "摘要與逐字稿已儲存"
            )
            return Result.success()
        }

        // Step 3: Send Email via Gmail SMTP
        record = record.copy(status = ProcessingStatus.SENDING_EMAIL.name)
        repo.updateRecord(record)

        return try {
            emailService.sendCallSummaryEmail(record, settings)
            var finalRecord: CallRecordEntity = record.copy(
                status = ProcessingStatus.COMPLETED.name,
                emailSent = true,
                emailSentAtMillis = System.currentTimeMillis(),
                emailRecipientSnapshot = settings.parsedRecipients().joinToString(", "),
                errorMessage = null
            )

            if (settings.deleteAudioAfterEmailSent) {
                runCatching { audioFile.delete() }
                finalRecord = finalRecord.copy(audioFilePath = "")
            }

            repo.updateRecord(finalRecord)
            val urgencyTag = if (finalRecord.urgencyLevel == "緊急") "【緊急】" else ""
            notifyUser(
                recordId,
                "${urgencyTag}已寄出通話摘要：${finalRecord.patientName}",
                "個管師：${finalRecord.caseManagerName}・已寄至 ${finalRecord.emailRecipientSnapshot}"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Email delivery failed for record $recordId", e)
            repo.updateRecord(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "摘要已完成，但寄信失敗：${e.message ?: "請檢查 Gmail 應用程式密碼"}"
                )
            )
            notifyUser(
                recordId,
                "摘要已完成，但寄信失敗",
                "請開啟 App 核對設定後點擊「重新寄信」。"
            )
            Result.failure()
        }
    }

    private fun notifyUser(recordId: Long, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_RECORD_ID, recordId)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            recordId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, TumorRecorderApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm?.notify((2000 + (recordId % 1000)).toInt(), notification)
    }

    companion object {
        private const val TAG = "CallProcessingWorker"
        const val KEY_RECORD_ID = "record_id"
        const val KEY_FORCE_EMAIL = "force_email"
    }
}
