package com.tumorrecorder.app.ui.home

import android.net.Uri
import android.provider.CallLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tumorrecorder.app.AppContainer
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.local.DeviceCallLogEntry
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.ProcessingStatus
import com.tumorrecorder.app.service.CallRecordingService
import com.tumorrecorder.app.service.RecordingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CallLogFilter(val label: String) {
    ALL("全部"),
    INCOMING("來電"),
    OUTGOING("撥出"),
    MISSED("未接")
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _dialNumber = MutableStateFlow("")
    val dialNumber: StateFlow<String> = _dialNumber.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _allDeviceCallLogs = MutableStateFlow<List<DeviceCallLogEntry>>(emptyList())

    private val _callLogFilter = MutableStateFlow(CallLogFilter.ALL)
    val callLogFilter: StateFlow<CallLogFilter> = _callLogFilter.asStateFlow()

    private val _hasCallLogPermission = MutableStateFlow(container.deviceCallLogRepository.hasCallLogPermission())
    val hasCallLogPermission: StateFlow<Boolean> = _hasCallLogPermission.asStateFlow()

    val filteredCallLogs: StateFlow<List<DeviceCallLogEntry>> = combine(
        _allDeviceCallLogs,
        _callLogFilter
    ) { logs, filter ->
        when (filter) {
            CallLogFilter.ALL -> logs
            CallLogFilter.INCOMING -> logs.filter { it.type == CallLog.Calls.INCOMING_TYPE }
            CallLogFilter.OUTGOING -> logs.filter { it.type == CallLog.Calls.OUTGOING_TYPE }
            CallLogFilter.MISSED -> logs.filter { it.isMissedOrRejected }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val recordingState: StateFlow<RecordingState> = CallRecordingService.recordingState

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    val promptTemplates: StateFlow<List<com.tumorrecorder.app.data.local.PromptTemplateEntity>> =
        container.promptTemplateRepository
            .observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val pendingConfirmRecord: StateFlow<CallRecordEntity?> = CallRecordingService.pendingConfirmRecordId
        .flatMapLatest { id ->
            if (id != null && id > 0L) container.callRecordRepository.observeRecord(id) else flowOf(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val allConsultationRecords = container.callRecordRepository.observeAllRecords()

    val consultationRecordCount: StateFlow<Int> = allConsultationRecords
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val pendingAnalysisCount: StateFlow<Int> = allConsultationRecords
        .map { list -> list.count { it.processingStatus == ProcessingStatus.PENDING } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    init {
        refreshCallLog()
    }

    fun refreshCallLog() {
        val granted = container.deviceCallLogRepository.hasCallLogPermission()
        _hasCallLogPermission.value = granted
        if (!granted) {
            _allDeviceCallLogs.value = emptyList()
            return
        }
        viewModelScope.launch {
            _allDeviceCallLogs.value = container.deviceCallLogRepository.loadRecentCalls(limit = 80)
        }
    }

    fun onCallLogFilterChanged(filter: CallLogFilter) {
        _callLogFilter.value = filter
    }

    fun onDialNumberChanged(value: String) {
        _dialNumber.value = value
    }

    fun appendDialDigit(digit: String) {
        _dialNumber.value += digit
    }

    fun backspaceDialDigit() {
        val current = _dialNumber.value
        if (current.isNotEmpty()) {
            _dialNumber.value = current.dropLast(1)
        }
    }

    fun importAudioFile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                container.callRecordRepository.importAudioUri(uri)
            }.onSuccess { importedId ->
                if (!container.settingsRepository.currentSettings().autoAnalyzeAfterCall) {
                    CallRecordingService.requestConfirmForRecord(importedId)
                } else {
                    _snackbarMessage.value = "已匯入錄音檔，正在排隊進行 AI 逐字稿與摘要分析"
                }
            }.onFailure { e ->
                _snackbarMessage.value = "匯入音檔失敗：${e.message ?: "請確認檔案格式"}"
            }
        }
    }

    fun confirmAnalyzeNow(
        recordId: Long,
        promptTemplateId: Long,
        includeTranscript: Boolean? = null,
        forceStandardTier: Boolean = false
    ) {
        CallRecordingService.clearPendingConfirm()
        container.callRecordRepository.enqueueProcessing(
            recordId = recordId,
            promptTemplateId = promptTemplateId,
            includeTranscript = includeTranscript,
            forceStandardTier = forceStandardTier
        )
        _snackbarMessage.value = if (forceStandardTier) {
            "已改用標準層立即開始 Gemini AI 分析，完成後將依設定自動寄信"
        } else {
            "已開始 Gemini AI 摘要分析，完成後將依設定自動寄信"
        }
    }

    fun dismissConfirmKeepRecord() {
        CallRecordingService.clearPendingConfirm()
        _snackbarMessage.value = "已保留錄音，您可隨時於「通話諮詢紀錄」中點擊開始分析"
    }

    fun discardRecord(record: CallRecordEntity) {
        viewModelScope.launch {
            CallRecordingService.clearPendingConfirm()
            container.callRecordRepository.deleteRecord(record)
            _snackbarMessage.value = "已刪除該筆錄音"
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(container) as T
                }
            }
    }
}
