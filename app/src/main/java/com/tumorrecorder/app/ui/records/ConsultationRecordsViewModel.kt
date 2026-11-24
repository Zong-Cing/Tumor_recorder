package com.tumorrecorder.app.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tumorrecorder.app.AppContainer
import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ConsultationRecordsViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    val promptTemplates: StateFlow<List<PromptTemplateEntity>> =
        container.promptTemplateRepository
            .observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

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

    fun analyzeWithPrompt(
        recordId: Long,
        promptTemplateId: Long,
        includeTranscript: Boolean? = null,
        forceStandardTier: Boolean = false
    ) {
        container.callRecordRepository.enqueueProcessing(
            recordId = recordId,
            promptTemplateId = promptTemplateId,
            includeTranscript = includeTranscript,
            forceStandardTier = forceStandardTier
        )
        _snackbarMessage.value = if (forceStandardTier) {
            "已使用標準層立即開始 AI 摘要分析"
        } else {
            "已使用選定 Prompt 加入 AI 摘要分析佇列"
        }
    }

    fun runStandardNow(recordId: Long) {
        container.callRecordRepository.switchToStandardNow(recordId = recordId)
        _snackbarMessage.value = "已取消 Flex 等待，改用標準層立即進行 AI 分析"
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ConsultationRecordsViewModel(container) as T
                }
            }
    }
}
