package com.tumorrecorder.app.domain.model

enum class RecordingAudioSource(
    val label: String,
    val description: String
) {
    VOICE_RECOGNITION(
        label = "VOICE_RECOGNITION（三星／無障礙推薦）",
        description = "Android 開放給無障礙服務於通話中不被靜音的音源，搭配原生系統降噪錄製個管師端最清晰"
    ),
    VOICE_COMMUNICATION(
        label = "VOICE_COMMUNICATION（通話通訊）",
        description = "語音通話音源，若無障礙音源異常可改試此項"
    ),
    MIC(
        label = "MIC（主麥克風）",
        description = "標準麥克風，適合面對面諮詢或開擴音錄音"
    ),
    CAMCORDER(
        label = "CAMCORDER（頂部麥克風）",
        description = "部分機型啟用頂部麥克風，部分三星機型通話中可能被系統靜音"
    ),
    UNPROCESSED(
        label = "UNPROCESSED（原始未處理）",
        description = "未經處理的原始音訊，多數三星機型在通話中會被系統靜音"
    );

    companion object {
        fun fromName(name: String?): RecordingAudioSource =
            entries.firstOrNull { it.name == name } ?: VOICE_RECOGNITION
    }
}

enum class GeminiThinkingLevel(
    val label: String,
    val description: String
) {
    AUTO_LOWEST(
        label = "自動最低（省費推薦）",
        description = "依模型自動套用最低支援等級：Hydrogen／3.8-flash 用 low，Flash-Lite／3.6-flash 用 minimal，比預設 high 省約 60%～75% 費用"
    ),
    LOW(
        label = "低 (low)",
        description = "固定使用低思考深度，速度快且大幅節省輸出費用"
    ),
    MEDIUM(
        label = "中 (medium)",
        description = "中等思考深度，平衡臨床推理品質與費用"
    ),
    HIGH(
        label = "高 (high)",
        description = "最高思考深度，適合極複雜案例但費用較高"
    );

    /**
     * Resolves a valid Gemini REST API `thinkingConfig.thinkingLevel` value for [modelId],
     * returning null if the model does not support `thinkingLevel` (e.g., gemini-2.*).
     * Never returns "MINIMAL" for models that only allow LOW/MEDIUM/HIGH (e.g., gemini-4-*, gemini-3.8-flash).
     */
    fun resolveApiThinkingLevel(modelId: String): String? {
        val normalized = modelId.trim().lowercase()
        if (normalized.startsWith("gemini-2.")) return null
        return when (this) {
            AUTO_LOWEST -> {
                val supportsMinimal = normalized.contains("flash-lite") ||
                    normalized.contains("3.6-flash") ||
                    normalized.contains("3.5-flash") ||
                    normalized.contains("3.0-flash")
                if (supportsMinimal) "MINIMAL" else "LOW"
            }
            LOW -> "LOW"
            MEDIUM -> "MEDIUM"
            HIGH -> "HIGH"
        }
    }

    companion object {
        fun fromName(name: String?): GeminiThinkingLevel =
            entries.firstOrNull { it.name == name } ?: AUTO_LOWEST
    }
}

data class AppSettings(
    val onlyAnalyzeAppCalls: Boolean = true,
    val autoRecordCalls: Boolean = false,
    val autoEnableSpeakerOnCall: Boolean = false,
    val audioSource: RecordingAudioSource = RecordingAudioSource.VOICE_RECOGNITION,
    val autoAnalyzeAfterCall: Boolean = false,
    val autoSendEmailAfterSummary: Boolean = true,
    val attachAudioToEmail: Boolean = false,
    val deleteAudioAfterEmailSent: Boolean = false,
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val geminiThinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.AUTO_LOWEST,
    val generateTranscriptByDefault: Boolean = false,
    val customPromptAppendix: String = "",
    val smtpHost: String = DEFAULT_SMTP_HOST,
    val smtpPort: Int = DEFAULT_SMTP_PORT,
    val senderEmail: String = "",
    val senderAppPassword: String = "",
    val recipientEmails: String = "",
    val defaultCaseManagerUnit: String = "腫瘤個案管理中心"
) {
    val isGeminiConfigured: Boolean
        get() = geminiApiKey.isNotBlank()

    val isAiConfigured: Boolean
        get() = isGeminiConfigured

    val isEmailConfigured: Boolean
        get() = senderEmail.isNotBlank() &&
            senderAppPassword.isNotBlank() &&
            recipientEmails.isNotBlank()

    fun parsedRecipients(): List<String> =
        recipientEmails
            .split(",", ";", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("@") }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-4-hydrogen"
        const val DEFAULT_SMTP_HOST = "smtp.gmail.com"
        const val DEFAULT_SMTP_PORT = 587
    }
}
