package com.tumorrecorder.app.data.remote

import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.PromptBuilder
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.OncologySummaryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Uploads audio via Gemini Files API, requests structured transcription + summary,
     * and deletes the temporary remote file in finally for medical privacy.
     */
    suspend fun transcribeAndSummarize(
        audioFile: File,
        mimeType: String,
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings,
        promptTemplate: PromptTemplateEntity? = null
    ): OncologySummaryResult = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey.trim()
        require(apiKey.isNotEmpty()) { "尚未設定 Gemini API Key，請先至「設定」頁面填入。" }
        require(audioFile.exists() && audioFile.length() > 0L) { "找不到有效的錄音檔案。" }

        val uploaded = uploadAudioFile(audioFile, mimeType, apiKey)
        try {
            waitUntilFileActive(uploaded.name, apiKey)
            val prompt = PromptBuilder.buildFromTemplate(
                template = promptTemplate,
                phoneNumber = phoneNumber,
                callTimeDisplay = callTimeDisplay,
                settings = settings
            )
            generateStructuredSummary(
                fileUri = uploaded.uri,
                mimeType = mimeType,
                prompt = prompt,
                model = settings.geminiModel,
                apiKey = apiKey
            )
        } finally {
            runCatching { deleteUploadedFile(uploaded.name, apiKey) }
        }
    }

    suspend fun testConnection(apiKey: String, model: String): String = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "請先輸入 Gemini API Key" }
        val targetModel = model.trim().ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }

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
            val text = GeminiResponseParser.extractCandidateText(raw)
            text.ifBlank { "連線成功 ($targetModel)" }
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

    private fun generateStructuredSummary(
        fileUri: String,
        mimeType: String,
        prompt: String,
        model: String,
        apiKey: String
    ): OncologySummaryResult {
        val targetModel = model.trim().ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("file_data", buildJsonObject {
                                put("mime_type", mimeType)
                                put("file_uri", fileUri)
                            })
                        })
                        add(buildJsonObject {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("responseSchema", buildResponseSchema())
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/v1beta/models/$targetModel:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gemini 摘要產生失敗 (${response.code}): ${extractApiError(raw)}")
            }
            return GeminiResponseParser.parseSummaryResponse(raw)
        }
    }

    private fun deleteUploadedFile(fileName: String, apiKey: String) {
        val request = Request.Builder()
            .url("$BASE_URL/v1beta/$fileName?key=$apiKey")
            .delete()
            .build()
        client.newCall(request).execute().close()
    }

    private fun buildResponseSchema(): JsonObject = buildJsonObject {
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
            put("transcript", stringSchema("完整繁體中文逐字稿，標記說話者"))
        })
        put("required", JsonArray(
            listOf(
                "patientName",
                "patientIdentifier",
                "identifierType",
                "caseManagerName",
                "callerRelation",
                "inquiryCategory",
                "urgencyLevel",
                "summary",
                "actionItems",
                "transcript"
            ).map { JsonPrimitive(it) }
        ))
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
                transcript = cleanedJson
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
