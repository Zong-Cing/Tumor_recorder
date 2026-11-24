package com.tumorrecorder.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tumorrecorder.app.AppContainer
import com.tumorrecorder.app.data.local.CallRecordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordDetailViewModel(
    private val recordId: Long,
    private val container: AppContainer
) : ViewModel() {

    val record: StateFlow<CallRecordEntity?> = container.callRecordRepository
        .observeRecord(recordId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val promptTemplates: StateFlow<List<com.tumorrecorder.app.data.local.PromptTemplateEntity>> =
        container.promptTemplateRepository
            .observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isGeneratingTranscript = MutableStateFlow(false)
    val isGeneratingTranscript: StateFlow<Boolean> = _isGeneratingTranscript.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    fun saveEdits(
        patientName: String,
        patientIdentifier: String,
        identifierType: String,
        caseManagerName: String,
        callerRelation: String,
        inquiryCategory: String,
        urgencyLevel: String,
        summary: String,
        actionItemsText: String
    ) {
        val current = record.value ?: return
        viewModelScope.launch {
            container.callRecordRepository.updateRecord(
                current.copy(
                    patientName = patientName.trim(),
                    patientIdentifier = patientIdentifier.trim(),
                    identifierType = identifierType.trim(),
                    caseManagerName = caseManagerName.trim(),
                    callerRelation = callerRelation.trim(),
                    inquiryCategory = inquiryCategory.trim(),
                    urgencyLevel = urgencyLevel.trim().ifBlank { "一般" },
                    summary = summary.trim(),
                    actionItemsText = actionItemsText.trim()
                )
            )
            _message.value = "已儲存核對修正內容"
        }
    }

    val recordingsDir: java.io.File
        get() = container.callRecordRepository.ensureRecordingsDir()

    fun saveSupplementAudio(file: java.io.File, durationSeconds: Long) {
        viewModelScope.launch {
            container.callRecordRepository.saveSupplementAudio(recordId, file, durationSeconds)
            _message.value = "已儲存口述補充摘要，點「更換 Prompt 重分析」即可合併整合至 AI 摘要"
        }
    }

    fun clearSupplementAudio() {
        viewModelScope.launch {
            container.callRecordRepository.clearSupplementAudio(recordId)
            _message.value = "已刪除口述補充錄音"
        }
    }

    fun reAnalyze(
        promptTemplateId: Long = -1L,
        includeTranscript: Boolean? = null,
        forceStandardTier: Boolean = false
    ) {
        container.callRecordRepository.enqueueProcessing(
            recordId = recordId,
            promptTemplateId = promptTemplateId,
            forceSendEmail = false,
            includeTranscript = includeTranscript,
            forceStandardTier = forceStandardTier
        )
        _message.value = when {
            forceStandardTier -> "已改用標準層立即排程重新進行 AI 分析"
            includeTranscript == true -> "已排程重新進行 AI 摘要與完整逐字稿分析"
            else -> "已使用選定 Prompt 排程重新進行 AI 精簡摘要分析"
        }
    }

    fun runStandardNow() {
        container.callRecordRepository.switchToStandardNow(recordId = recordId)
        _message.value = "已取消 Flex 等待，改用標準層立即進行 AI 分析"
    }

    fun generateTranscriptOnly() {
        val current = record.value ?: return
        if (_isGeneratingTranscript.value) return

        val validAudio = current.audioFilePath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        if (validAudio == null || !validAudio.exists() || validAudio.length() == 0L) {
            _message.value = "找不到本機錄音檔，無法補產逐字稿"
            return
        }
        val supplementFile = current.supplementAudioFilePath
            .takeIf { it.isNotBlank() }
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() && it.length() > 0L }

        viewModelScope.launch {
            _isGeneratingTranscript.value = true
            runCatching {
                val settings = container.settingsRepository.currentSettings()
                val callTimeDisplay = com.tumorrecorder.app.data.remote.EmailFormatter.formatTimestamp(current.startedAtMillis)
                val mime = current.audioMimeType.ifBlank { "audio/mp4" }
                container.geminiService.transcribeOnly(
                    audioFile = validAudio,
                    mimeType = mime,
                    phoneNumber = current.phoneNumber,
                    callTimeDisplay = callTimeDisplay,
                    settings = settings,
                    supplementAudioFile = supplementFile
                )
            }.onSuccess { transcript ->
                val latest = container.callRecordRepository.getRecord(recordId) ?: current
                container.callRecordRepository.updateRecord(latest.copy(transcript = transcript))
                _message.value = "已完成完整逐字稿（原有摘要與編輯內容已保留）"
            }.onFailure { e ->
                _message.value = "產生逐字稿失敗：${e.message ?: "請檢查網路或 API Key"}"
            }
            _isGeneratingTranscript.value = false
        }
    }

    fun resendEmail() {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                container.callRecordRepository.resendEmailOnly(recordId)
            }.onSuccess {
                _message.value = "已成功寄出通話紀錄信件"
            }.onFailure { e ->
                _message.value = "寄信失敗：${e.message ?: "請檢查設定"}"
            }
            _isBusy.value = false
        }
    }

    fun deleteRecord() {
        val current = record.value ?: return
        viewModelScope.launch {
            container.callRecordRepository.deleteRecord(current)
            _deleted.value = true
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        fun factory(recordId: Long, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return RecordDetailViewModel(recordId, container) as T
                }
            }
    }
}
