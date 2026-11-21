package com.tumorrecorder.app.domain.model

enum class AiProvider(val label: String) {
    GEMINI("Google Gemini"),
    GROQ("Groq 免費版 (Whisper + Qwen/GPT-OSS)");

    companion object {
        fun fromName(name: String?): AiProvider =
            entries.firstOrNull { it.name == name } ?: GEMINI
    }
}

enum class VoiceBoostLevel(
    val label: String,
    val shortLabel: String,
    val maxBoostDb: Double,
    val description: String
) {
    OFF(
        label = "關閉（原始音量）",
        shortLabel = "關閉",
        maxBoostDb = 0.0,
        description = "不做軟體音量增強，完全保留麥克風原始錄音大小"
    ),
    STANDARD(
        label = "標準增強（最高 +18 dB）",
        shortLabel = "標準 +18dB",
        maxBoostDb = 18.0,
        description = "自動放大對方小聲語音（約 8 倍）並限制本端爆音，適合安靜環境"
    ),
    STRONG(
        label = "強力增強（最高 +30 dB・推薦）",
        shortLabel = "強力 +30dB",
        maxBoostDb = 30.0,
        description = "針對聽筒通話遠端極小聲強化動態壓縮與自動增益（最高約 31 倍），同時限制本端不破音"
    );

    companion object {
        fun fromName(name: String?): VoiceBoostLevel =
            entries.firstOrNull { it.name == name } ?: STRONG
    }
}

enum class RecordingAudioSource(
    val label: String,
    val description: String
) {
    VOICE_RECOGNITION(
        label = "VOICE_RECOGNITION（三星／無障礙首選）",
        description = "Android 唯一開放給無障礙服務於通話中不被靜音的音源！建議固定選用此項並搭配「強力 +30dB 遠端增強」"
    ),
    CAMCORDER(
        label = "CAMCORDER（頂部麥克風・僅部分機型）",
        description = "部分機型可啟用靠近聽筒的頂部麥克風；但三星通話中可能被系統靜音（若偵測無聲將自動切回 VOICE_RECOGNITION）"
    ),
    VOICE_COMMUNICATION(
        label = "VOICE_COMMUNICATION（通話通訊）",
        description = "語音通話音源，系統通常會開回音消除，若偵測被系統靜音將自動切回 VOICE_RECOGNITION"
    ),
    MIC(
        label = "MIC（主麥克風）",
        description = "標準麥克風，適合非通話之面對面錄音；通話中若被系統靜音將自動切回 VOICE_RECOGNITION"
    ),
    UNPROCESSED(
        label = "UNPROCESSED（原始未處理・三星通話易靜音）",
        description = "未經處理的原始音訊，多數三星機型在通話中會被 Android 強制靜音（若無聲會自動切回 VOICE_RECOGNITION）"
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
    val boostCallVolumeOnRecord: Boolean = true,
    val voiceBoostLevel: VoiceBoostLevel = VoiceBoostLevel.STRONG,
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
