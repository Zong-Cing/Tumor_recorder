package com.tumorrecorder.app.domain.model

enum class AiProvider(val label: String) {
    GEMINI("Google Gemini"),
    GROQ("Groq 免費版 (Whisper + Qwen/GPT-OSS)");

    companion object {
        fun fromName(name: String?): AiProvider =
            entries.firstOrNull { it.name == name } ?: GEMINI
    }
}

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

data class AppSettings(
    val onlyAnalyzeAppCalls: Boolean = true,
    val autoRecordCalls: Boolean = false,
    val autoEnableSpeakerOnCall: Boolean = false,
    val audioSource: RecordingAudioSource = RecordingAudioSource.VOICE_RECOGNITION,
    val autoAnalyzeAfterCall: Boolean = false,
    val autoSendEmailAfterSummary: Boolean = true,
    val attachAudioToEmail: Boolean = false,
    val deleteAudioAfterEmailSent: Boolean = false,
    val aiProvider: AiProvider = AiProvider.GEMINI,
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val groqApiKey: String = "",
    val groqSttModel: String = DEFAULT_GROQ_STT_MODEL,
    val groqLlmModel: String = DEFAULT_GROQ_LLM_MODEL,
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

    val isGroqConfigured: Boolean
        get() = groqApiKey.isNotBlank()

    val isAiConfigured: Boolean
        get() = when (aiProvider) {
            AiProvider.GEMINI -> isGeminiConfigured
            AiProvider.GROQ -> isGroqConfigured
        }

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
        const val DEFAULT_GROQ_STT_MODEL = "whisper-large-v3-turbo"
        const val DEFAULT_GROQ_LLM_MODEL = "qwen/qwen3.8-27b"
        const val DEFAULT_SMTP_HOST = "smtp.gmail.com"
        const val DEFAULT_SMTP_PORT = 587
    }
}
