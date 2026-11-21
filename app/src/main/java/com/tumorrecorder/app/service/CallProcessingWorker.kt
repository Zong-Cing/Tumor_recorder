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
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Reliable background worker that runs:
 *   1. AI audio upload/STT + structured oncology transcription & summary (Gemini or Groq)
 *   2. Persisting structured fields & full transcript to Room DB
 *   3. Automatic Gmail SMTP delivery (if enabled & configured)
 */
class CallProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)
        val promptTemplateId = inputData.getLong(KEY_PROMPT_TEMPLATE_ID, -1L)
        val forceEmail = inputData.getBoolean(KEY_FORCE_EMAIL, false)
        Log.i(TAG, "doWork start: recordId=$recordId promptTemplateId=$promptTemplateId forceEmail=$forceEmail")

        if (recordId <= 0L) {
            Log.e(TAG, "Invalid recordId=$recordId")
            notifyUser(1L, "無法進行 AI 摘要", "通話紀錄編號無效（recordId=$recordId）。")
            return Result.failure()
        }

        val app = applicationContext as TumorRecorderApp
        val repo = app.container.callRecordRepository
        val promptRepo = app.container.promptTemplateRepository
        val settings = app.container.settingsRepository.currentSettings()
        val geminiService = app.container.geminiService
        val groqService = app.container.groqService
        val emailService = app.container.emailService

        var record = repo.getRecord(recordId)
        if (record == null) {
            Log.e(TAG, "Record id=$recordId not found in database")
            notifyUser(recordId, "找不到通話紀錄", "資料庫中找不到此筆錄音（id=$recordId）。")
            return Result.failure()
        }

        return try {
            processRecord(
                recordId = recordId,
                promptTemplateId = promptTemplateId,
                forceEmail = forceEmail,
                initialRecord = record,
                repo = repo,
                promptRepo = promptRepo,
                settings = settings,
                geminiService = geminiService,
                groqService = groqService,
                emailService = emailService
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Uncaught failure while processing record $recordId", t)
            runCatching {
                repo.getRecord(recordId)?.let { latest ->
                    repo.updateRecord(
                        latest.copy(
                            status = ProcessingStatus.FAILED.name,
                            errorMessage = "背景處理發生異常：${t.message ?: t::class.java.simpleName}"
                        )
                    )
                }
            }
            notifyUser(recordId, "通話處理異常", t.message ?: t::class.java.simpleName)
            Result.failure()
        }
    }

    private suspend fun processRecord(
        recordId: Long,
        promptTemplateId: Long,
        forceEmail: Boolean,
        initialRecord: CallRecordEntity,
        repo: com.tumorrecorder.app.data.repository.CallRecordRepository,
        promptRepo: com.tumorrecorder.app.data.repository.PromptTemplateRepository,
        settings: com.tumorrecorder.app.domain.model.AppSettings,
        geminiService: com.tumorrecorder.app.data.remote.GeminiService,
        groqService: com.tumorrecorder.app.data.remote.GroqService,
        emailService: com.tumorrecorder.app.data.remote.EmailService
    ): Result {
        var record = initialRecord

        if (!settings.isAiConfigured) {
            val missingName = if (settings.aiProvider == com.tumorrecorder.app.domain.model.AiProvider.GROQ) {
                "Groq API Key"
            } else {
                "Gemini API Key"
            }
            Log.w(TAG, "AI not configured ($missingName) for recordId=$recordId")
            repo.updateRecord(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "尚未設定 $missingName，請先至「設定」填寫後再點擊重新分析。"
                )
            )
            notifyUser(recordId, "無法進行 AI 摘要", "請先至設定頁填入 $missingName。")
            return Result.failure()
        }

        val audioFile = File(record.audioFilePath)
        val audioLength = if (audioFile.exists()) audioFile.length() else 0L
        Log.i(TAG, "Loaded recordId=$recordId provider=${settings.aiProvider.name} audio=${audioFile.absolutePath} bytes=$audioLength")
        if (!audioFile.exists() || audioLength == 0L) {
            Log.e(TAG, "Audio file missing or empty for recordId=$recordId path=${record.audioFilePath}")
            repo.updateRecord(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "找不到錄音檔案（${record.audioFilePath}），可能已被移除。"
                )
            )
            notifyUser(recordId, "找不到錄音檔案", "該筆通話錄音檔不存在或大小為 0。")
            return Result.failure()
        }

        val chosenTemplate = if (promptTemplateId > 0L) {
            promptRepo.getById(promptTemplateId) ?: promptRepo.getDefaultOrFirst()
        } else {
            promptRepo.getDefaultOrFirst()
        }

        // Step 1: Transcribe & Summarize via chosen AI provider (Gemini or Groq)
        record = record.copy(
            status = ProcessingStatus.TRANSCRIBING.name,
            promptTemplateName = chosenTemplate.name,
            errorMessage = null
        )
        repo.updateRecord(record)

        val summaryResult = try {
            val callTimeDisplay = EmailFormatter.formatTimestamp(record.startedAtMillis)
            val mime = record.audioMimeType.ifBlank { "audio/mp4" }
            when (settings.aiProvider) {
                com.tumorrecorder.app.domain.model.AiProvider.GROQ -> {
                    groqService.transcribeAndSummarize(
                        audioFile = audioFile,
                        mimeType = mime,
                        phoneNumber = record.phoneNumber,
                        callTimeDisplay = callTimeDisplay,
                        settings = settings,
                        promptTemplate = chosenTemplate
                    )
                }
                com.tumorrecorder.app.domain.model.AiProvider.GEMINI -> {
                    geminiService.transcribeAndSummarize(
                        audioFile = audioFile,
                        mimeType = mime,
                        phoneNumber = record.phoneNumber,
                        callTimeDisplay = callTimeDisplay,
                        settings = settings,
                        promptTemplate = chosenTemplate
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI processing failed for record $recordId: ${e.message}", e)
            repo.updateRecord(
                record.copy(
                    status = ProcessingStatus.FAILED.name,
                    errorMessage = "AI 分析失敗：${e.message ?: "請檢查網路或 API Key"}"
                )
            )
            notifyUser(recordId, "通話 AI 摘要失敗", e.message ?: "請開啟 App 檢視並重試。")
            return Result.failure()
        }

        // Step 2: Persist extracted oncology case manager details (preserving in-call quick notes if AI returned 未提及)
        val mergedPatientName = mergeField(summaryResult.patientName, record.patientName)
        val mergedIdentifier = mergeField(summaryResult.patientIdentifier, record.patientIdentifier)
        val mergedCaseManager = mergeField(summaryResult.caseManagerName, record.caseManagerName)

        record = record.copy(
            patientName = mergedPatientName,
            patientIdentifier = mergedIdentifier,
            identifierType = summaryResult.identifierType.ifBlank { "未提及" },
            caseManagerName = mergedCaseManager,
            callerRelation = summaryResult.callerRelation.ifBlank { "未提及" },
            inquiryCategory = summaryResult.inquiryCategory.ifBlank { "一般諮詢" },
            urgencyLevel = summaryResult.urgencyLevel.ifBlank { "一般" },
            summary = summaryResult.summary,
            actionItemsText = summaryResult.actionItems.joinToString("\n"),
            transcript = summaryResult.transcript,
            promptTemplateName = chosenTemplate.name,
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

    private fun mergeField(aiValue: String, prefilledValue: String): String {
        val cleanedAi = aiValue.trim()
        val cleanedPre = prefilledValue.trim()
        return when {
            cleanedAi.isNotEmpty() && cleanedAi != "未提及" -> cleanedAi
            cleanedPre.isNotEmpty() -> cleanedPre
            else -> "未提及"
        }
    }

    companion object {
        private const val TAG = "TumorRecorder_Worker"
        const val KEY_RECORD_ID = "record_id"
        const val KEY_PROMPT_TEMPLATE_ID = "prompt_template_id"
        const val KEY_FORCE_EMAIL = "force_email"
    }
}
