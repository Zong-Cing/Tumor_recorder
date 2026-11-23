package com.tumorrecorder.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tumorrecorder.app.AppContainer
import com.tumorrecorder.app.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer
) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    private val _testingGemini = MutableStateFlow(false)
    val testingGemini: StateFlow<Boolean> = _testingGemini.asStateFlow()

    private val _testingEmail = MutableStateFlow(false)
    val testingEmail: StateFlow<Boolean> = _testingEmail.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        container.settingsRepository.updateSettings(transform)
    }

    fun testGeminiConnection() {
        val s = settings.value
        viewModelScope.launch {
            _testingGemini.value = true
            runCatching {
                container.geminiService.testConnection(s.geminiApiKey, s.geminiModel)
            }.onSuccess { reply ->
                _feedbackMessage.value = "Gemini API 測試成功：$reply"
            }.onFailure { e ->
                val raw = e.message ?: "請檢查 API Key"
                _feedbackMessage.value = if (raw.contains("503")) {
                    "Gemini (${s.geminiModel}) 目前滿載 503，可改選 gemini-3.5-flash-lite（實際分析時也會自動降級重試）"
                } else {
                    "Gemini 連線失敗：$raw"
                }
            }
            _testingGemini.value = false
        }
    }

    fun sendTestEmail() {
        val s = settings.value
        viewModelScope.launch {
            _testingEmail.value = true
            runCatching {
                container.emailService.sendTestEmail(s)
            }.onSuccess {
                _feedbackMessage.value = "測試信件已成功寄出至 ${s.parsedRecipients().joinToString(", ")}"
            }.onFailure { e ->
                _feedbackMessage.value = "測試寄信失敗：${e.message ?: "請檢查 Gmail 應用程式密碼"}"
            }
            _testingEmail.value = false
        }
    }

    fun clearFeedback() {
        _feedbackMessage.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(container) as T
                }
            }
    }
}
