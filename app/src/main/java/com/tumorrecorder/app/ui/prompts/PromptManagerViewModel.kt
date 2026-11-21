package com.tumorrecorder.app.ui.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tumorrecorder.app.AppContainer
import com.tumorrecorder.app.data.local.PromptTemplateEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PromptManagerViewModel(
    private val container: AppContainer
) : ViewModel() {

    val templates: StateFlow<List<PromptTemplateEntity>> = container.promptTemplateRepository
        .observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            container.promptTemplateRepository.ensureSeeded()
        }
    }

    fun saveTemplate(
        id: Long,
        name: String,
        description: String,
        promptContent: String,
        isDefault: Boolean
    ) {
        viewModelScope.launch {
            runCatching {
                container.promptTemplateRepository.saveTemplate(
                    id = id,
                    name = name,
                    description = description,
                    promptContent = promptContent,
                    isDefault = isDefault
                )
            }.onSuccess {
                _message.value = if (id > 0L) "已更新 Prompt 範本" else "已新增 Prompt 範本"
            }.onFailure { e ->
                _message.value = e.message ?: "儲存失敗"
            }
        }
    }

    fun setDefault(id: Long) {
        viewModelScope.launch {
            container.promptTemplateRepository.setDefault(id)
            _message.value = "已設為預設分析 Prompt"
        }
    }

    fun deleteTemplate(template: PromptTemplateEntity) {
        viewModelScope.launch {
            val deleted = container.promptTemplateRepository.deleteTemplate(template)
            _message.value = if (deleted) {
                "已刪除「${template.name}」"
            } else {
                "至少需保留一組分析 Prompt 範本"
            }
        }
    }

    fun restoreMissingDefaults() {
        viewModelScope.launch {
            container.promptTemplateRepository.restoreMissingDefaults()
            _message.value = "已補回缺少的內建預設範本"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PromptManagerViewModel(container) as T
                }
            }
    }
}
