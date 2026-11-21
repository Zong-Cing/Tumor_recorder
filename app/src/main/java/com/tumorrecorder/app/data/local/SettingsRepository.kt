package com.tumorrecorder.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tumorrecorder.app.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores app settings and credentials (Gemini API key & Gmail App Password)
 * in Android Keystore-backed EncryptedSharedPreferences.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = createSecurePrefs(context.applicationContext)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun currentSettings(): AppSettings = _settings.value

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        saveSettings(updated)
        _settings.value = updated
    }

    private fun loadSettings(): AppSettings {
        return AppSettings(
            onlyAnalyzeAppCalls = prefs.getBoolean(KEY_ONLY_APP_CALLS, true),
            autoRecordCalls = prefs.getBoolean(KEY_AUTO_RECORD, false),
            autoEnableSpeakerOnCall = prefs.getBoolean(KEY_AUTO_SPEAKER, false),
            boostCallVolumeOnRecord = prefs.getBoolean(KEY_BOOST_CALL_VOLUME, true),
            voiceBoostLevel = com.tumorrecorder.app.domain.model.VoiceBoostLevel.fromName(
                prefs.getString(KEY_VOICE_BOOST_LEVEL, null)
            ),
            audioSource = com.tumorrecorder.app.domain.model.RecordingAudioSource.fromName(
                prefs.getString(KEY_AUDIO_SOURCE, null)
            ),
            autoAnalyzeAfterCall = prefs.getBoolean(KEY_AUTO_ANALYZE, false),
            autoSendEmailAfterSummary = prefs.getBoolean(KEY_AUTO_EMAIL, true),
            attachAudioToEmail = prefs.getBoolean(KEY_ATTACH_AUDIO, false),
            deleteAudioAfterEmailSent = prefs.getBoolean(KEY_DELETE_AUDIO_AFTER_EMAIL, false),
            aiProvider = com.tumorrecorder.app.domain.model.AiProvider.fromName(
                prefs.getString(KEY_AI_PROVIDER, null)
            ),
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, AppSettings.DEFAULT_GEMINI_MODEL)
                ?.ifBlank { AppSettings.DEFAULT_GEMINI_MODEL }
                ?: AppSettings.DEFAULT_GEMINI_MODEL,
            groqApiKey = prefs.getString(KEY_GROQ_API_KEY, "") ?: "",
            groqSttModel = prefs.getString(KEY_GROQ_STT_MODEL, AppSettings.DEFAULT_GROQ_STT_MODEL)
                ?.ifBlank { AppSettings.DEFAULT_GROQ_STT_MODEL }
                ?: AppSettings.DEFAULT_GROQ_STT_MODEL,
            groqLlmModel = prefs.getString(KEY_GROQ_LLM_MODEL, AppSettings.DEFAULT_GROQ_LLM_MODEL)
                ?.ifBlank { AppSettings.DEFAULT_GROQ_LLM_MODEL }
                ?: AppSettings.DEFAULT_GROQ_LLM_MODEL,
            customPromptAppendix = prefs.getString(KEY_CUSTOM_PROMPT, "") ?: "",
            smtpHost = prefs.getString(KEY_SMTP_HOST, AppSettings.DEFAULT_SMTP_HOST)
                ?.ifBlank { AppSettings.DEFAULT_SMTP_HOST }
                ?: AppSettings.DEFAULT_SMTP_HOST,
            smtpPort = prefs.getInt(KEY_SMTP_PORT, AppSettings.DEFAULT_SMTP_PORT),
            senderEmail = prefs.getString(KEY_SENDER_EMAIL, "") ?: "",
            senderAppPassword = prefs.getString(KEY_SENDER_PASSWORD, "") ?: "",
            recipientEmails = prefs.getString(KEY_RECIPIENT_EMAILS, "") ?: "",
            defaultCaseManagerUnit = prefs.getString(KEY_UNIT_NAME, "腫瘤個案管理中心")
                ?.ifBlank { "腫瘤個案管理中心" }
                ?: "腫瘤個案管理中心"
        )
    }

    private fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_ONLY_APP_CALLS, s.onlyAnalyzeAppCalls)
            .putBoolean(KEY_AUTO_RECORD, s.autoRecordCalls)
            .putBoolean(KEY_AUTO_SPEAKER, s.autoEnableSpeakerOnCall)
            .putBoolean(KEY_BOOST_CALL_VOLUME, s.boostCallVolumeOnRecord)
            .putString(KEY_VOICE_BOOST_LEVEL, s.voiceBoostLevel.name)
            .putString(KEY_AUDIO_SOURCE, s.audioSource.name)
            .putBoolean(KEY_AUTO_ANALYZE, s.autoAnalyzeAfterCall)
            .putBoolean(KEY_AUTO_EMAIL, s.autoSendEmailAfterSummary)
            .putBoolean(KEY_ATTACH_AUDIO, s.attachAudioToEmail)
            .putBoolean(KEY_DELETE_AUDIO_AFTER_EMAIL, s.deleteAudioAfterEmailSent)
            .putString(KEY_AI_PROVIDER, s.aiProvider.name)
            .putString(KEY_GEMINI_API_KEY, s.geminiApiKey.trim())
            .putString(KEY_GEMINI_MODEL, s.geminiModel.trim().ifBlank { AppSettings.DEFAULT_GEMINI_MODEL })
            .putString(KEY_GROQ_API_KEY, s.groqApiKey.trim())
            .putString(KEY_GROQ_STT_MODEL, s.groqSttModel.trim().ifBlank { AppSettings.DEFAULT_GROQ_STT_MODEL })
            .putString(KEY_GROQ_LLM_MODEL, s.groqLlmModel.trim().ifBlank { AppSettings.DEFAULT_GROQ_LLM_MODEL })
            .putString(KEY_CUSTOM_PROMPT, s.customPromptAppendix)
            .putString(KEY_SMTP_HOST, s.smtpHost.trim().ifBlank { AppSettings.DEFAULT_SMTP_HOST })
            .putInt(KEY_SMTP_PORT, s.smtpPort)
            .putString(KEY_SENDER_EMAIL, s.senderEmail.trim())
            .putString(KEY_SENDER_PASSWORD, s.senderAppPassword.replace(" ", "").trim())
            .putString(KEY_RECIPIENT_EMAILS, s.recipientEmails.trim())
            .putString(KEY_UNIT_NAME, s.defaultCaseManagerUnit.trim().ifBlank { "腫瘤個案管理中心" })
            .apply()
    }

    private fun createSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed; falling back to private prefs", e)
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "tumor_recorder_secure_prefs"

        private const val KEY_ONLY_APP_CALLS = "only_analyze_app_calls"
        private const val KEY_AUTO_RECORD = "auto_record_calls"
        private const val KEY_AUTO_SPEAKER = "auto_enable_speaker"
        private const val KEY_BOOST_CALL_VOLUME = "boost_call_volume_on_record"
        private const val KEY_VOICE_BOOST_LEVEL = "voice_boost_level"
        private const val KEY_AUDIO_SOURCE = "recording_audio_source"
        private const val KEY_AUTO_ANALYZE = "auto_analyze_after_call"
        private const val KEY_AUTO_EMAIL = "auto_send_email"
        private const val KEY_ATTACH_AUDIO = "attach_audio_to_email"
        private const val KEY_DELETE_AUDIO_AFTER_EMAIL = "delete_audio_after_email"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GROQ_STT_MODEL = "groq_stt_model"
        private const val KEY_GROQ_LLM_MODEL = "groq_llm_model"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt_appendix"
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_SENDER_EMAIL = "sender_email"
        private const val KEY_SENDER_PASSWORD = "sender_app_password"
        private const val KEY_RECIPIENT_EMAILS = "recipient_emails"
        private const val KEY_UNIT_NAME = "default_unit_name"
    }
}
