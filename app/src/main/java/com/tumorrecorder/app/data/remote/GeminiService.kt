package com.tumorrecorder.app.data.remote

import android.util.Log
import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.PromptBuilder
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.GeminiThinkingLevel
import com.tumorrecorder.app.domain.model.OncologySummaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class GeminiServiceTier(val apiValue: String?) {
    STANDARD(null),
    FLEX("flex")
}

class FlexOverloadedException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

object FlexBackoffPolicy {
    const val MAX_FLEX_WAIT_MILLIS: Long = 30L * 60L * 1000L // 30 minutes
    private const val MIN_REMAINING_FOR_RETRY_MILLIS: Long = 30_000L
    private val DELAYS_SECONDS = longArrayOf(30L, 60L, 120L, 240L, 480L, 600L)

    /**
     * Returns the exponential backoff delay in milliseconds for the next Flex retry,
     * or null when the 30-minute Flex wait budget has been exhausted (caller should switch to Standard).
     */
    fun nextDelayMillis(attempt: Int, elapsedMillis: Long): Long? {
        val remaining = MAX_FLEX_WAIT_MILLIS - elapsedMillis.coerceAtLeast(0L)
        if (remaining < MIN_REMAINING_FOR_RETRY_MILLIS) return null
        val idx = attempt.coerceIn(0, DELAYS_SECONDS.lastIndex)
        val rawDelay = DELAYS_SECONDS[idx] * 1000L
        return minOf(rawDelay, remaining).coerceAtLeast(15_000L)
    }

    fun formatDelay(delayMillis: Long): String {
        val totalSec = (delayMillis / 1000L).coerceAtLeast(1L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return when {
            mins > 0L && secs > 0L -> "${mins} 分 ${secs} 秒"
            mins > 0L -> "${mins} 分鐘"
            else -> "${secs} 秒"
        }
    }
}

class GeminiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()
) {
    private val flexClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(600, TimeUnit.SECONDS)
            .build()
    }

    private val quickFlexClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Uploads audio via Gemini Files API, requests structured summary (with optional transcript),
     * and deletes temporary remote files in finally for medical privacy.
     *
     * When [serviceTier] is [GeminiServiceTier.FLEX], throws [FlexOverloadedException] on 503/429/500
     * or timeout so the caller can perform exponential backoff retry and auto-fallback to Standard.
     */
    suspend fun transcribeAndSummarize(
        audioFile: File,
        mimeType: String,
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings,
        promptTemplate: PromptTemplateEntity? = null,
        supplementAudioFile: File? = null,
        includeTranscript: Boolean = settings.generateTranscriptByDefault,
        serviceTier: GeminiServiceTier = GeminiServiceTier.STANDARD
    ): OncologySummaryResult = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey.trim()
        require(apiKey.isNotEmpty()) { "尚未設定 Gemini API Key，請先至「設定」頁面填入。" }
        require(audioFile.exists() && audioFile.length() > 0L) { "找不到有效的錄音檔案。" }

        val validSupplement = supplementAudioFile?.takeIf { it.exists() && it.length() > 0L }
        val uploadedMain = uploadAudioFile(audioFile, mimeType, apiKey)
        var uploadedSupplement: UploadedGeminiFile? = null
        try {
            if (validSupplement != null) {
                uploadedSupplement = uploadAudioFile(validSupplement, "audio/mp4", apiKey)
            }
            waitUntilFileActive(uploadedMain.name, apiKey)
            uploadedSupplement?.let { waitUntilFileActive(it.name, apiKey) }

            val prompt = PromptBuilder.buildFromTemplate(
                template = promptTemplate,
                phoneNumber = phoneNumber,
                callTimeDisplay = callTimeDisplay,
                settings = settings,
                hasSupplementAudio = uploadedSupplement != null,
                includeTranscript = includeTranscript
            )
            generateStructuredSummary(
                fileUri = uploadedMain.uri,
                mimeType = mimeType,
                supplementFileUri = uploadedSupplement?.uri,
                prompt = prompt,
                model = settings.geminiModel,
                thinkingLevel = settings.geminiThinkingLevel,
                includeTranscript = includeTranscript,
                serviceTier = serviceTier,
                apiKey = apiKey
            )
        } finally {
            runCatching { deleteUploadedFile(uploadedMain.name, apiKey) }
            uploadedSupplement?.let { sup ->
                runCatching { deleteUploadedFile(sup.name, apiKey) }
            }
        }
    }

    /**
     * On-demand full verbatim transcription without overwriting existing structured summary.
     * If Flex tier is enabled in settings, tries 1 quick Flex attempt (90s) and falls back
     * immediately to Standard on overload so the interactive UI stays responsive.
     */
    suspend fun transcribeOnly(
        audioFile: File,
        mimeType: String,
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings,
        supplementAudioFile: File? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey.trim()
        require(apiKey.isNotEmpty()) { "尚未設定 Gemini API Key，請先至「設定」頁面填入。" }
        require(audioFile.exists() && audioFile.length() > 0L) { "找不到有效的錄音檔案。" }

        val validSupplement = supplementAudioFile?.takeIf { it.exists() && it.length() > 0L }
        val uploadedMain = uploadAudioFile(audioFile, mimeType, apiKey)
        var uploadedSupplement: UploadedGeminiFile? = null
        try {
            if (validSupplement != null) {
                uploadedSupplement = uploadAudioFile(validSupplement, "audio/mp4", apiKey)
            }
            waitUntilFileActive(uploadedMain.name, apiKey)
            uploadedSupplement?.let { waitUntilFileActive(it.name, apiKey) }

            val prompt = PromptBuilder.buildTranscriptOnlyPrompt(
                phoneNumber = phoneNumber,
                callTimeDisplay = callTimeDisplay,
                settings = settings,
                hasSupplementAudio = uploadedSupplement != null
            )
            generatePlainTranscript(
                fileUri = uploadedMain.uri,
                mimeType = mimeType,
                supplementFileUri = uploadedSupplement?.uri,
                prompt = prompt,
                model = settings.geminiModel,
                thinkingLevel = settings.geminiThinkingLevel,
                tryQuickFlexFirst = settings.useFlexTier,
                apiKey = apiKey
            )
        } finally {
            runCatching { deleteUploadedFile(uploadedMain.name, apiKey) }
            uploadedSupplement?.let { sup ->
                runCatching { deleteUploadedFile(sup.name, apiKey) }
            }
        }
    }

    suspend fun testConnection(
        apiKey: String,
        model: String,
        thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.AUTO_LOWEST
    ): String = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "請先輸入 Gemini API Key" }
        val targetModel = model.trim().ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }
        val apiThinkingLevel = thinkingLevel.resolveApiThinkingLevel(targetModel)

        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "請僅回覆：連線成功")
                        })
                    })
                })
            })
            if (apiThinkingLevel != null) {
                put("generationConfig", buildJsonObject {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", apiThinkingLevel)
                    })
                })
            }
        }

        val request = Request.Builder()
            .url("$BASE_URL/v1beta/models/$targetModel:generateContent?key=$trimmedKey")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gemini API 錯誤 (${response.code}): ${extractApiError(raw)}")
            }
            logUsageMetadata(targetModel, apiThinkingLevel, mode = "test", rawBody = raw)
            val text = GeminiResponseParser.extractCandidateText(raw)
            val suffix = apiThinkingLevel?.let { " [$targetModel / thinking=$it]" } ?: " [$targetModel]"
            "${text.ifBlank { "連線成功" }}$suffix"
        }
    }

    private data class UploadedGeminiFile(val name: String, val uri: String)

    private fun uploadAudioFile(file: File, mimeType: String, apiKey: String): UploadedGeminiFile {
        val fileLength = file.length()
        val metadataJson = buildJsonObject {
            put("file", buildJsonObject {
                put("display_name", file.name)
            })
        }.toString()

        // Step 1: Initiate resumable upload
        val initRequest = Request.Builder()
            .url("$BASE_URL/upload/v1beta/files?key=$apiKey")
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", fileLength.toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val uploadUrl = client.newCall(initRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("音檔上傳初始化失敗 (${response.code}): ${extractApiError(raw)}")
            }
            response.header("X-Goog-Upload-URL")
                ?: throw IOException("Gemini 未回傳上傳位址 (X-Goog-Upload-URL)")
        }

        // Step 2: Upload audio bytes and finalize
        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()

        client.newCall(uploadRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("音檔上傳失敗 (${response.code}): ${extractApiError(raw)}")
            }
            val root = json.parseToJsonElement(raw).jsonObject
            val fileObj = root["file"]?.jsonObject ?: root
            val name = fileObj["name"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("上傳回應缺少 file.name")
            val uri = fileObj["uri"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("上傳回應缺少 file.uri")
            return UploadedGeminiFile(name = name, uri = uri)
        }
    }

    private suspend fun waitUntilFileActive(fileName: String, apiKey: String) {
        repeat(20) {
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/$fileName?key=$apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("查詢上傳音檔狀態失敗 (${response.code}): ${extractApiError(raw)}")
                }
                val obj = json.parseToJsonElement(raw).jsonObject
                when (obj["state"]?.jsonPrimitive?.contentOrNull) {
                    "ACTIVE" -> return
                    "FAILED" -> throw IOException("Gemini 處理上傳音檔失敗")
                    else -> Unit
                }
            }
            delay(1500L)
        }
    }

    private suspend fun generateStructuredSummary(
        fileUri: String,
        mimeType: String,
        supplementFileUri: String?,
        prompt: String,
        model: String,
        thinkingLevel: GeminiThinkingLevel,
        includeTranscript: Boolean,
        serviceTier: GeminiServiceTier,
        apiKey: String
    ): OncologySummaryResult {
        val primaryModel = model.trim().ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }
        val contentsArray = buildAudioContents(
            fileUri = fileUri,
            mimeType = mimeType,
            supplementFileUri = supplementFileUri,
            prompt = prompt
        )
        val responseSchema = buildResponseSchema(includeTranscript = includeTranscript)

        // When Flex tier is requested, try the primary model on Flex with 600s read timeout.
        // If Flex is overloaded (503/429/500 or timeout), throw FlexOverloadedException so
        // WorkManager performs exponential backoff retry up to 30 min before switching to Standard.
        if (serviceTier == GeminiServiceTier.FLEX) {
            val apiThinkingLevel = thinkingLevel.resolveApiThinkingLevel(primaryModel)
            val flexPayload = buildSummaryPayload(
                contentsArray = contentsArray,
                responseSchema = responseSchema,
                apiThinkingLevel = apiThinkingLevel,
                serviceTier = GeminiServiceTier.FLEX
            )
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/models/$primaryModel:generateContent?key=$apiKey")
                .post(flexPayload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                flexClient.executeCancellable(request).use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        logUsageMetadata(
                            model = primaryModel,
                            thinkingLevel = apiThinkingLevel,
                            tier = "flex",
                            mode = if (includeTranscript) "summary+transcript" else "summary-only",
                            rawBody = raw
                        )
                        return GeminiResponseParser.parseSummaryResponse(raw)
                    }
                    val errMsg = extractApiError(raw)
                    val isFlexOverload = response.code == 503 || response.code == 429 || response.code == 500
                    if (isFlexOverload) {
                        throw FlexOverloadedException("Gemini Flex ($primaryModel) 暫時滿載 (${response.code}): $errMsg")
                    }
                    val isFlexUnsupported = response.code == 400 && (
                        errMsg.contains("serviceTier", ignoreCase = true) ||
                            errMsg.contains("service_tier", ignoreCase = true) ||
                            errMsg.contains("flex", ignoreCase = true)
                        )
                    if (isFlexUnsupported) {
                        runCatching {
                            Log.w(TAG, "Flex tier unsupported on $primaryModel ($errMsg); falling back to Standard")
                        }
                    } else {
                        throw IOException("Gemini ($primaryModel) 錯誤 (${response.code}): $errMsg")
                    }
                }
            } catch (ste: SocketTimeoutException) {
                throw FlexOverloadedException("Gemini Flex ($primaryModel) 排隊逾時，將自動重試", ste)
            }
        }

        val candidateModels = listOf(
            primaryModel,
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite"
        ).distinct()

        var lastError: IOException? = null
        for ((modelIdx, targetModel) in candidateModels.withIndex()) {
            val apiThinkingLevel = thinkingLevel.resolveApiThinkingLevel(targetModel)
            val payloadString = buildSummaryPayload(
                contentsArray = contentsArray,
                responseSchema = responseSchema,
                apiThinkingLevel = apiThinkingLevel,
                serviceTier = GeminiServiceTier.STANDARD
            )

            val attemptsForModel = if (modelIdx == 0) 2 else 1
            repeat(attemptsForModel) { attempt ->
                val request = Request.Builder()
                    .url("$BASE_URL/v1beta/models/$targetModel:generateContent?key=$apiKey")
                    .post(payloadString.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.executeCancellable(request).use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        logUsageMetadata(
                            model = targetModel,
                            thinkingLevel = apiThinkingLevel,
                            tier = "standard",
                            mode = if (includeTranscript) "summary+transcript" else "summary-only",
                            rawBody = raw
                        )
                        return GeminiResponseParser.parseSummaryResponse(raw)
                    }
                    val errMsg = extractApiError(raw)
                    lastError = IOException("Gemini ($targetModel) 錯誤 (${response.code}): $errMsg")

                    // Retry or fall back only on transient overload / quota / unavailable errors
                    val isRetryable = response.code == 503 || response.code == 429 || response.code == 500
                    if (!isRetryable) {
                        throw lastError!!
                    }
                    if (attempt < attemptsForModel - 1) {
                        delay(3000L * (attempt + 1))
                    }
                }
            }
            if (modelIdx < candidateModels.lastIndex) {
                delay(1000L)
            }
        }
        throw lastError ?: IOException("Gemini 摘要產生失敗（伺服器滿載 503），建議至設定頁改選 gemini-3.5-flash-lite 後再重試。")
    }

    private suspend fun generatePlainTranscript(
        fileUri: String,
        mimeType: String,
        supplementFileUri: String?,
        prompt: String,
        model: String,
        thinkingLevel: GeminiThinkingLevel,
        tryQuickFlexFirst: Boolean,
        apiKey: String
    ): String {
        val primaryModel = model.trim().ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }
        val contentsArray = buildAudioContents(
            fileUri = fileUri,
            mimeType = mimeType,
            supplementFileUri = supplementFileUri,
            prompt = prompt
        )

        if (tryQuickFlexFirst) {
            val apiThinkingLevel = thinkingLevel.resolveApiThinkingLevel(primaryModel)
            val flexPayload = buildPlainTextPayload(
                contentsArray = contentsArray,
                apiThinkingLevel = apiThinkingLevel,
                serviceTier = GeminiServiceTier.FLEX
            )
            val request = Request.Builder()
                .url("$BASE_URL/v1beta/models/$primaryModel:generateContent?key=$apiKey")
                .post(flexPayload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val flexResult = runCatching {
                quickFlexClient.executeCancellable(request).use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        logUsageMetadata(
                            model = primaryModel,
                            thinkingLevel = apiThinkingLevel,
                            tier = "flex",
                            mode = "transcript-only",
                            rawBody = raw
                        )
                        GeminiResponseParser.stripCodeFence(GeminiResponseParser.extractCandidateText(raw))
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (!flexResult.isNullOrBlank()) {
                return flexResult
            }
        }

        val candidateModels = listOf(
            primaryModel,
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite"
        ).distinct()

        var lastError: IOException? = null
        for ((modelIdx, targetModel) in candidateModels.withIndex()) {
            val apiThinkingLevel = thinkingLevel.resolveApiThinkingLevel(targetModel)
            val payloadString = buildPlainTextPayload(
                contentsArray = contentsArray,
                apiThinkingLevel = apiThinkingLevel,
                serviceTier = GeminiServiceTier.STANDARD
            )

            val attemptsForModel = if (modelIdx == 0) 2 else 1
            repeat(attemptsForModel) { attempt ->
                val request = Request.Builder()
                    .url("$BASE_URL/v1beta/models/$targetModel:generateContent?key=$apiKey")
                    .post(payloadString.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.executeCancellable(request).use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        logUsageMetadata(
                            model = targetModel,
                            thinkingLevel = apiThinkingLevel,
                            tier = "standard",
                            mode = "transcript-only",
                            rawBody = raw
                        )
                        val text = GeminiResponseParser.extractCandidateText(raw)
                        return GeminiResponseParser.stripCodeFence(text)
                    }
                    val errMsg = extractApiError(raw)
                    lastError = IOException("Gemini ($targetModel) 錯誤 (${response.code}): $errMsg")

                    val isRetryable = response.code == 503 || response.code == 429 || response.code == 500
                    if (!isRetryable) {
                        throw lastError!!
                    }
                    if (attempt < attemptsForModel - 1) {
                        delay(3000L * (attempt + 1))
                    }
                }
            }
            if (modelIdx < candidateModels.lastIndex) {
                delay(1000L)
            }
        }
        throw lastError ?: IOException("Gemini 逐字稿產生失敗，請稍後重試。")
    }

    private fun buildSummaryPayload(
        contentsArray: JsonArray,
        responseSchema: JsonObject,
        apiThinkingLevel: String?,
        serviceTier: GeminiServiceTier
    ): String = buildJsonObject {
        put("contents", contentsArray)
        put("generationConfig", buildJsonObject {
            put("responseMimeType", "application/json")
            put("responseSchema", responseSchema)
            if (apiThinkingLevel != null) {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", apiThinkingLevel)
                })
            }
        })
        serviceTier.apiValue?.let { put("serviceTier", it) }
    }.toString()

    private fun buildPlainTextPayload(
        contentsArray: JsonArray,
        apiThinkingLevel: String?,
        serviceTier: GeminiServiceTier
    ): String = buildJsonObject {
        put("contents", contentsArray)
        if (apiThinkingLevel != null) {
            put("generationConfig", buildJsonObject {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", apiThinkingLevel)
                })
            })
        }
        serviceTier.apiValue?.let { put("serviceTier", it) }
    }.toString()

    private suspend fun OkHttpClient.executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private fun buildAudioContents(
        fileUri: String,
        mimeType: String,
        supplementFileUri: String?,
        prompt: String
    ): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("parts", buildJsonArray {
                if (supplementFileUri != null) {
                    add(buildJsonObject {
                        put("text", "【音檔 1：原始通話錄音】")
                    })
                }
                add(buildJsonObject {
                    put("file_data", buildJsonObject {
                        put("mime_type", mimeType)
                        put("file_uri", fileUri)
                    })
                })
                if (supplementFileUri != null) {
                    add(buildJsonObject {
                        put("text", "【音檔 2：個管師通話結束後的口述補充摘要（請作為最高優先權核對依據）】")
                    })
                    add(buildJsonObject {
                        put("file_data", buildJsonObject {
                            put("mime_type", "audio/mp4")
                            put("file_uri", supplementFileUri)
                        })
                    })
                }
                add(buildJsonObject {
                    put("text", prompt)
                })
            })
        })
    }

    private fun logUsageMetadata(
        model: String,
        thinkingLevel: String?,
        mode: String,
        rawBody: String,
        tier: String = "standard"
    ) {
        runCatching {
            val usage = json.parseToJsonElement(rawBody).jsonObject["usageMetadata"]?.jsonObject ?: return
            val promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.contentOrNull ?: "?"
            val candidateTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.contentOrNull ?: "?"
            val thoughtTokens = usage["thoughtsTokenCount"]?.jsonPrimitive?.contentOrNull ?: "0"
            val totalTokens = usage["totalTokenCount"]?.jsonPrimitive?.contentOrNull ?: "?"
            Log.i(
                TAG,
                "Gemini usage [$mode/$tier] model=$model thinking=${thinkingLevel ?: "default"} " +
                    "prompt=$promptTokens candidates=$candidateTokens thoughts=$thoughtTokens total=$totalTokens"
            )
        }
    }

    private fun deleteUploadedFile(fileName: String, apiKey: String) {
        val request = Request.Builder()
            .url("$BASE_URL/v1beta/$fileName?key=$apiKey")
            .delete()
            .build()
        client.newCall(request).execute().close()
    }

    private fun buildResponseSchema(includeTranscript: Boolean): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("patientName", stringSchema("病人姓名，未提及請填「未提及」"))
            put("patientIdentifier", stringSchema("病歷號碼或身分證字號，未提及請填「未提及」"))
            put("identifierType", stringSchema("病歷號碼、身分證字號、兩者皆有、或未提及"))
            put("caseManagerName", stringSchema("所屬腫瘤個管師姓名，未提及請填「未提及」"))
            put("callerRelation", stringSchema("來電者身分（病人本人、家屬、醫護同仁等）"))
            put("inquiryCategory", stringSchema("主要諮詢分類"))
            put("urgencyLevel", stringSchema("僅可為：一般、需追蹤、緊急"))
            put("summary", stringSchema("繁體中文條列式來電討論摘要"))
            put("actionItems", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject { put("type", "STRING") })
                put("description", "後續待辦或追蹤事項清單")
            })
            if (includeTranscript) {
                put("transcript", stringSchema("完整繁體中文逐字稿，標記說話者"))
            }
        })
        val requiredFields = buildList {
            add("patientName")
            add("patientIdentifier")
            add("identifierType")
            add("caseManagerName")
            add("callerRelation")
            add("inquiryCategory")
            add("urgencyLevel")
            add("summary")
            add("actionItems")
            if (includeTranscript) {
                add("transcript")
            }
        }
        put("required", JsonArray(requiredFields.map { JsonPrimitive(it) }))
    }

    private fun stringSchema(description: String): JsonObject = buildJsonObject {
        put("type", "STRING")
        put("description", description)
    }

    private fun extractApiError(rawBody: String): String {
        return runCatching {
            val obj = json.parseToJsonElement(rawBody).jsonObject
            obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: rawBody.take(300)
    }

    companion object {
        private const val TAG = "TumorRecorder_Gemini"
        private const val BASE_URL = "https://generativelanguage.googleapis.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Pure parser for Gemini generateContent JSON responses; isolated for unit testing.
 */
object GeminiResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun extractCandidateText(rawResponse: String): String {
        val root = json.parseToJsonElement(rawResponse).jsonObject
        val candidates = root["candidates"]?.jsonArray
            ?: throw IOException("Gemini 回應中找不到 candidates 欄位")
        if (candidates.isEmpty()) {
            throw IOException("Gemini 未回傳任何分析結果（可能遭安全過濾器攔截）")
        }
        val parts = candidates[0].jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
            ?: throw IOException("Gemini 回應中缺少 content.parts")

        val nonThoughtParts = parts.filterNot {
            it.jsonObject["thought"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
        }
        val effectiveParts = nonThoughtParts.ifEmpty { parts }

        return effectiveParts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n")
            .trim()
    }

    fun parseSummaryResponse(rawResponse: String): OncologySummaryResult {
        val candidateText = extractCandidateText(rawResponse)
        val cleanedJson = stripCodeFence(candidateText)
        return runCatching {
            json.decodeFromString(OncologySummaryResult.serializer(), cleanedJson)
        }.getOrElse {
            // Fallback if model returned plain text instead of strict JSON
            OncologySummaryResult(
                summary = cleanedJson,
                transcript = ""
            )
        }
    }

    internal fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewLine = trimmed.indexOf('\n')
        if (firstNewLine == -1) return trimmed
        val withoutStart = trimmed.substring(firstNewLine + 1)
        val lastFence = withoutStart.lastIndexOf("```")
        return if (lastFence >= 0) {
            withoutStart.substring(0, lastFence).trim()
        } else {
            withoutStart.trim()
        }
    }
}
