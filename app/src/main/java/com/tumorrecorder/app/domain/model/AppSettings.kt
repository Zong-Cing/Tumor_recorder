package com.tumorrecorder.app.domain.model

enum class RecordingAudioSource(
    val label: String,
    val description: String
) {
    VOICE_RECOGNITION(
        label = "VOICE_RECOGNITION（無障礙推薦）",
        description = "搭配無障礙服務於通話中併發收音，最推薦用於免開擴音通話錄音"
    ),
    VOICE_COMMUNICATION(
        label = "VOICE_COMMUNICATION（通話通訊）",
        description = "針對語音通話最佳化並啟用回音消除，若無障礙音源無聲可改試此項"
    ),
    MIC(
        label = "MIC（主麥克風）",
        description = "標準麥克風收音，適合搭配開擴音或面對面諮詢錄音"
    ),
    UNPROCESSED(
        label = "UNPROCESSED（原始未處理）",
        description = "未經系統降噪與濾波的原始音訊，適合部分高階機型測試"
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
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
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
