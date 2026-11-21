package com.tumorrecorder.app.data.remote

import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.PromptBuilder
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.OncologySummaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Free-tier friendly Groq API pipeline (no credit card required):
 *   1. Speech-to-Text via Whisper (/openai/v1/audio/transcriptions, e.g. whisper-large-v3-turbo)
 *   2. Structured oncology JSON summary via Chat Completions (/openai/v1/chat/completions, e.g. qwen/qwen3.8-27b)
 */
class GroqService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun transcribeAndSummarize(
        audioFile: File,
        mimeType: String,
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings,
        promptTemplate: PromptTemplateEntity? = null
    ): OncologySummaryResult = withContext(Dispatchers.IO) {
        val apiKey = settings.groqApiKey.trim()
        require(apiKey.isNotEmpty()) { "尚未設定 Groq API Key，請先至「設定」頁面填入。" }
        require(audioFile.exists() && audioFile.length() > 0L) { "找不到有效的錄音檔案。" }

        // Step 1: Whisper speech-to-text
        val rawTranscript = transcribeWithWhisper(
            audioFile = audioFile,
            mimeType = mimeType,
            sttModel = settings.groqSttModel,
            apiKey = apiKey
        )

        // Step 2: Structured oncology summary using the chosen Prompt template
        val basePrompt = PromptBuilder.buildFromTemplate(
            template = promptTemplate,
            phoneNumber = phoneNumber,
            callTimeDisplay = callTimeDisplay,
            settings = settings
        )

        val userMessage = """
$basePrompt

【以下為 Whisper 語音轉錄之原始逐字稿，請依據此內容產出嚴格 JSON，並保留整理後的完整逐字稿於 transcript 欄位】
$rawTranscript
        """.trimIndent()

        val result = summarizeWithGroqLlm(
            promptText = userMessage,
            llmModel = settings.groqLlmModel,
            apiKey = apiKey
        )

        // Ensure transcript is never empty even if LLM truncated it
        if (result.transcript.isBlank()) {
            result.copy(transcript = rawTranscript)
        } else {
            result
        }
    }

    suspend fun testConnection(apiKey: String, llmModel: String): String = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "請先輸入 Groq API Key" }
        val targetModel = llmModel.trim().ifBlank { AppSettings.DEFAULT_GROQ_LLM_MODEL }

        val body = buildJsonObject {
            put("model", targetModel)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "請僅回覆：連線成功")
                })
            })
            put("max_tokens", 32)
        }

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $trimmedKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Groq API 錯誤 (${response.code}): ${extractError(raw)}")
            }
            val content = extractChatContent(raw)
            content.ifBlank { "連線成功 ($targetModel)" }
        }
    }

    private fun transcribeWithWhisper(
        audioFile: File,
        mimeType: String,
        sttModel: String,
        apiKey: String
    ): String {
        val targetStt = sttModel.trim().ifBlank { AppSettings.DEFAULT_GROQ_STT_MODEL }
        val mediaType = mimeType.ifBlank { "audio/mp4" }.toMediaType()

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
            .addFormDataPart("model", targetStt)
            .addFormDataPart("language", "zh")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart(
                "prompt",
                "台灣醫院腫瘤個案管理師與病人或家屬的繁體中文通話，包含病人姓名、病歷號碼、身分證字號、個管師、化療、標靶、副作用、發燒、急診、門診排程。"
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Groq Whisper 語音轉錄失敗 (${response.code}): ${extractError(raw)}")
            }
            return formatWhisperVerboseJson(raw)
        }
    }

    private fun summarizeWithGroqLlm(
        promptText: String,
        llmModel: String,
        apiKey: String
    ): OncologySummaryResult {
        val targetModel = llmModel.trim().ifBlank { AppSettings.DEFAULT_GROQ_LLM_MODEL }
        val body = buildJsonObject {
            put("model", targetModel)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "你是專業台灣醫院腫瘤個管助理。請嚴格只輸出合法 JSON 物件，包含欄位：patientName, patientIdentifier, identifierType, caseManagerName, callerRelation, inquiryCategory, urgencyLevel, summary, actionItems (字串陣列), transcript。所有文字一律使用繁體中文。"
                    )
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", promptText)
                })
            })
            put("response_format", buildJsonObject {
                put("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Groq 摘要產生失敗 (${response.code}): ${extractError(raw)}")
            }
            val content = extractChatContent(raw)
            val cleaned = GeminiResponseParser.stripCodeFence(content)
            return runCatching {
                json.decodeFromString(OncologySummaryResult.serializer(), cleaned)
            }.getOrElse {
                OncologySummaryResult(
                    summary = cleaned,
                    transcript = cleaned
                )
            }
        }
    }

    private fun formatWhisperVerboseJson(raw: String): String {
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val segments = root["segments"]?.jsonArray
            if (!segments.isNullOrEmpty()) {
                segments.mapNotNull { seg ->
                    val obj = seg.jsonObject
                    val startSec = obj["start"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (text.isEmpty()) null else "[${EmailFormatter.formatDuration(startSec)}] $text"
                }.joinToString("\n")
            } else {
                root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            }
        }.getOrElse { raw.trim() }
    }

    private fun extractChatContent(raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"]?.jsonArray
            ?: throw IOException("Groq 回應中缺少 choices 欄位")
        if (choices.isEmpty()) throw IOException("Groq 未回傳分析結果")
        return choices[0].jsonObject["message"]?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    private fun extractError(rawBody: String): String {
        return runCatching {
            val obj = json.parseToJsonElement(rawBody).jsonObject
            obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: rawBody.take(300)
    }

    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
