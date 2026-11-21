package com.tumorrecorder.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tumorrecorder.app.AppContainer
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.service.CallRecordingService
import com.tumorrecorder.app.service.RecordingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _dialNumber = MutableStateFlow("")
    val dialNumber: StateFlow<String> = _dialNumber.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    val recordingState: StateFlow<RecordingState> = CallRecordingService.recordingState

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    val records: StateFlow<List<CallRecordEntity>> = _searchQuery
        .flatMapLatest { query -> container.callRecordRepository.searchRecords(query) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onSearchQueryChanged(value: String) {
        _searchQuery.value = value
    }

    fun onDialNumberChanged(value: String) {
        _dialNumber.value = value
    }

    fun importAudioFile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                container.callRecordRepository.importAudioUri(uri)
            }.onSuccess {
                _snackbarMessage.value = "已匯入錄音檔，正在排隊進行 AI 逐字稿與摘要分析"
            }.onFailure { e ->
                _snackbarMessage.value = "匯入音檔失敗：${e.message ?: "請確認檔案格式"}"
            }
        }
    }

    fun retryProcessing(recordId: Long) {
        container.callRecordRepository.enqueueProcessing(recordId)
        _snackbarMessage.value = "已重新加入 AI 摘要分析佇列"
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
