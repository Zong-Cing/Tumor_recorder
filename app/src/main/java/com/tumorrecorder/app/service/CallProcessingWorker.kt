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
import com.tumorrecorder.app.data.remote.FlexBackoffPolicy
import com.tumorrecorder.app.data.remote.FlexOverloadedException
import com.tumorrecorder.app.data.remote.GeminiServiceTier
import com.tumorrecorder.app.domain.model.ProcessingStatus
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Reliable background worker that runs:
 *   1. Gemini audio upload + structured oncology summary (with Flex 50% off + 30-min exponential backoff -> Standard fallback)
 *   2. Persisting structured fields & optional transcript to Room DB
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
        val forceStandardTier = inputData.getBoolean(KEY_FORCE_STANDARD, false)
        val flexAttempt = inputData.getInt(KEY_FLEX_ATTEMPT, 0)
        val flexStartedAtInput = inputData.getLong(KEY_FLEX_STARTED_AT_MILLIS, 0L)
        val hasExplicitTranscriptFlag = inputData.keyValueMap.containsKey(KEY_INCLUDE_TRANSCRIPT)
        val explicitIncludeTranscript = inputData.getBoolean(KEY_INCLUDE_TRANSCRIPT, false)
        Log.i(
            TAG,
            "doWork start: recordId=$recordId promptTemplateId=$promptTemplateId forceEmail=$forceEmail " +
                "forceStandard=$forceStandardTier flexAttempt=$flexAttempt " +
                "includeTranscriptOverride=${if (hasExplicitTranscriptFlag) explicitIncludeTranscript else "default"}"
        )

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
        val emailService = app.container.emailService
        val includeTranscript = if (hasExplicitTranscriptFlag) {
            explicitIncludeTranscript
        } else {
            settings.generateTranscriptByDefault
        }

        val record = repo.getRecord(recordId)
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
                includeTranscript = includeTranscript,
                forceStandardTier = forceStandardTier,
                flexAttempt = flexAttempt,
                flexStartedAtInput = flexStartedAtInput,
                initialRecord = record,
                repo = repo,
                promptRepo = promptRepo,
                settings = settings,
                geminiService = geminiService,
                emailService = emailService
            )
        } catch (ce: CancellationException) {
            Log.i(TAG, "Worker cancelled for recordId=$recordId (likely switched to Standard tier or replaced)")
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
        includeTranscript: Boolean,
        forceStandardTier: Boolean,
        flexAttempt: Int,
        flexStartedAtInput: Long,
        initialRecord: CallRecordEntity,
        repo: com.tumorrecorder.app.data.repository.CallRecordRepository,
        promptRepo: com.tumorrecorder.app.data.repository.PromptTemplateRepository,
        settings: com.tumorrecorder.app.domain.model.AppSettings,
        geminiService: com.tumorrecorder.app.data.remote.GeminiService,
        emailService: com.tumorrecorder.app.data.remote.EmailService
    ): Result {
        var record = initialRecord

        if (!settings.isGeminiConfigured) {
            Log.w(TAG, "Gemini API Key not configured for recordId=$recordId")
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
        val audioLength = if (audioFile.exists()) audioFile.length() else 0L
        val supplementFile = record.supplementAudioFilePath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
        val flexStartedAt = if (flexStartedAtInput > 0L) flexStartedAtInput else System.currentTimeMillis()
        val elapsedMillis = (System.currentTimeMillis() - flexStartedAt).coerceAtLeast(0L)
        val useFlexThisRun = settings.useFlexTier &&
            !forceStandardTier &&
            elapsedMillis < FlexBackoffPolicy.MAX_FLEX_WAIT_MILLIS

        Log.i(
            TAG,
            "Loaded recordId=$recordId model=${settings.geminiModel} thinking=${settings.geminiThinkingLevel} " +
                "useFlex=$useFlexThisRun flexAttempt=$flexAttempt elapsedSec=${elapsedMillis / 1000L} " +
                "includeTranscript=$includeTranscript audio=${audioFile.absolutePath} " +
                "bytes=$audioLength hasSupplement=${supplementFile != null}"
        )
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

        val chosenTemplate = when {
            promptTemplateId > 0L -> promptRepo.getById(promptTemplateId) ?: promptRepo.getDefaultOrFirst()
            record.promptTemplateName.isNotBlank() ->
                promptRepo.getAll().firstOrNull { it.name == record.promptTemplateName }
                    ?: promptRepo.getDefaultOrFirst()
            else -> promptRepo.getDefaultOrFirst()
        }

        val callTimeDisplay = EmailFormatter.formatTimestamp(record.startedAtMillis)
        val mime = record.audioMimeType.ifBlank { "audio/mp4" }

        // Step 1: Summarize (and optionally transcribe) via Gemini — Flex first with 30-min exponential backoff -> Standard
        val summaryResult = if (useFlexThisRun) {
            val attemptText = if (flexAttempt == 0) {
                "正在使用 Flex 半價層分析中（省 50% 費用；若不想等待可隨時點「改用標準層」立即分析）"
            } else {
                val elapsedMin = (elapsedMillis / 60_000L).coerceAtLeast(1L)
                "Flex 半價層第 ${flexAttempt + 1} 次自動重試中（已排隊約 ${elapsedMin} 分鐘，滿 30 分鐘將自動切回標準層）"
            }
            record = record.copy(
                status = ProcessingStatus.WAITING_FLEX.name,
                promptTemplateName = chosenTemplate.name,
                errorMessage = attemptText
            )
            repo.updateRecord(record)

            try {
                geminiService.transcribeAndSummarize(
                    audioFile = audioFile,
                    mimeType = mime,
                    phoneNumber = record.phoneNumber,
                    callTimeDisplay = callTimeDisplay,
                    settings = settings,
                    promptTemplate = chosenTemplate,
                    supplementAudioFile = supplementFile,
                    includeTranscript = includeTranscript,
                    serviceTier = GeminiServiceTier.FLEX
                )
            } catch (foe: FlexOverloadedException) {
                val elapsedNow = (System.currentTimeMillis() - flexStartedAt).coerceAtLeast(0L)
                val nextDelayMs = FlexBackoffPolicy.nextDelayMillis(flexAttempt, elapsedNow)
                if (nextDelayMs != null) {
                    val waitLabel = FlexBackoffPolicy.formatDelay(nextDelayMs)
                    val remainingMin = ((FlexBackoffPolicy.MAX_FLEX_WAIT_MILLIS - elapsedNow) / 60_000L).coerceAtLeast(1L)
                    Log.w(TAG, "Flex overloaded on attempt $flexAttempt for record $recordId; retrying in $waitLabel")
                    repo.updateRecord(
                        record.copy(
                            status = ProcessingStatus.WAITING_FLEX.name,
                            promptTemplateName = chosenTemplate.name,
                            errorMessage = "Flex 半價層暫時滿載 (503)，將於 $waitLabel 後自動重試（第 ${flexAttempt + 1} 次；約 $remainingMin 分鐘後自動切標準層，或可隨時點「改用標準層」立即完成）"
                        )
                    )
                    repo.enqueueProcessing(
                        recordId = recordId,
                        promptTemplateId = chosenTemplate.id,
                        forceSendEmail = forceEmail,
                        includeTranscript = includeTranscript,
                        forceStandardTier = false,
                        flexAttempt = flexAttempt + 1,
                        flexStartedAtMillis = flexStartedAt,
                        initialDelayMillis = nextDelayMs,
                        appendInsteadOfReplace = true
                    )
                    return Result.success()
                }

                // 30-minute Flex window exhausted -> fall back automatically to Standard right now!
                Log.i(TAG, "Flex 30-minute window exhausted for record $recordId; switching automatically to Standard tier")
                record = record.copy(
                    status = ProcessingStatus.TRANSCRIBING.name,
                    promptTemplateName = chosenTemplate.name,
                    errorMessage = null
                )
                repo.updateRecord(record)
                try {
                    geminiService.transcribeAndSummarize(
                        audioFile = audioFile,
                        mimeType = mime,
                        phoneNumber = record.phoneNumber,
                        callTimeDisplay = callTimeDisplay,
                        settings = settings,
                        promptTemplate = chosenTemplate,
                        supplementAudioFile = supplementFile,
                        includeTranscript = includeTranscript,
                        serviceTier = GeminiServiceTier.STANDARD
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Standard fallback failed for record $recordId: ${e.message}", e)
                    repo.updateRecord(
                        record.copy(
                            status = ProcessingStatus.FAILED.name,
                            errorMessage = "AI 分析失敗：${e.message ?: "請檢查網路或 API Key"}"
                        )
                    )
                    notifyUser(recordId, "通話 AI 摘要失敗", e.message ?: "請開啟 App 檢視並重試。")
                    return Result.failure()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "AI processing failed on Flex for record $recordId: ${e.message}", e)
                repo.updateRecord(
                    record.copy(
                        status = ProcessingStatus.FAILED.name,
                        errorMessage = "AI 分析失敗：${e.message ?: "請檢查網路或 API Key"}"
                    )
                )
                notifyUser(recordId, "通話 AI 摘要失敗", e.message ?: "請開啟 App 檢視並重試。")
                return Result.failure()
            }
        } else {
            record = record.copy(
                status = ProcessingStatus.TRANSCRIBING.name,
                promptTemplateName = chosenTemplate.name,
                errorMessage = null
            )
            repo.updateRecord(record)

            try {
                geminiService.transcribeAndSummarize(
                    audioFile = audioFile,
                    mimeType = mime,
                    phoneNumber = record.phoneNumber,
                    callTimeDisplay = callTimeDisplay,
                    settings = settings,
                    promptTemplate = chosenTemplate,
                    supplementAudioFile = supplementFile,
                    includeTranscript = includeTranscript,
                    serviceTier = GeminiServiceTier.STANDARD
                )
            } catch (ce: CancellationException) {
                throw ce
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
        }

        // Step 2: Persist extracted oncology case manager details (preserving in-call quick notes & any existing transcript)
        val mergedPatientName = mergeField(summaryResult.patientName, record.patientName)
        val mergedIdentifier = mergeField(summaryResult.patientIdentifier, record.patientIdentifier)
        val mergedCaseManager = mergeField(summaryResult.caseManagerName, record.caseManagerName)
        val mergedTranscript = summaryResult.transcript.trim().ifBlank { record.transcript }

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
            transcript = mergedTranscript,
            promptTemplateName = chosenTemplate.name,
            errorMessage = null
        )

        val shouldEmail = (settings.autoSendEmailAfterSummary || forceEmail) && settings.isEmailConfigured
        if (!shouldEmail) {
            val saved = record.copy(status = ProcessingStatus.COMPLETED_NO_EMAIL.name)
            repo.updateRecord(saved)
            val contentDesc = if (saved.transcript.isBlank()) "摘要已儲存（精簡模式，可於詳情頁隨時補產逐字稿）" else "摘要與逐字稿已儲存"
            notifyUser(
                recordId,
                "通話摘要已完成：${saved.patientName}",
                if (!settings.isEmailConfigured) "$contentDesc（尚未設定 Gmail SMTP）" else contentDesc
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
                supplementFile?.let { runCatching { it.delete() } }
                finalRecord = finalRecord.copy(
                    audioFilePath = "",
                    supplementAudioFilePath = "",
                    supplementDurationSeconds = 0L
                )
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
        const val KEY_INCLUDE_TRANSCRIPT = "include_transcript"
        const val KEY_FORCE_STANDARD = "force_standard_tier"
        const val KEY_FLEX_ATTEMPT = "flex_attempt"
        const val KEY_FLEX_STARTED_AT_MILLIS = "flex_started_at_millis"
    }
}
