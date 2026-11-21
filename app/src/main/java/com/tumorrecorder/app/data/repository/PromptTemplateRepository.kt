package com.tumorrecorder.app.data.repository

import com.tumorrecorder.app.data.local.PromptTemplateDao
import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.DefaultPromptTemplates
import kotlinx.coroutines.flow.Flow

class PromptTemplateRepository(
    private val dao: PromptTemplateDao
) {
    fun observeAll(): Flow<List<PromptTemplateEntity>> = dao.observeAll()

    suspend fun ensureSeeded() {
        if (dao.count() == 0) {
            dao.insertAll(DefaultPromptTemplates.initialTemplates())
        }
    }

    suspend fun getAll(): List<PromptTemplateEntity> {
        ensureSeeded()
        return dao.getAll()
    }

    suspend fun getById(id: Long): PromptTemplateEntity? {
        ensureSeeded()
        return dao.getById(id)
    }

    suspend fun getDefaultOrFirst(): PromptTemplateEntity {
        ensureSeeded()
        return dao.getDefault()
            ?: dao.getAll().firstOrNull()
            ?: DefaultPromptTemplates.initialTemplates().first()
    }

    suspend fun saveTemplate(
        id: Long = 0L,
        name: String,
        description: String,
        promptContent: String,
        isDefault: Boolean
    ): Long {
        val cleanName = name.trim().ifBlank { "未命名分析範本" }
        val cleanDesc = description.trim()
        val cleanContent = promptContent.trim()
        require(cleanContent.isNotEmpty()) { "Prompt 內容不能為空白" }

        if (isDefault) {
            dao.clearAllDefaults()
        }

        val entity = PromptTemplateEntity(
            id = id,
            name = cleanName,
            description = cleanDesc,
            promptContent = cleanContent,
            isDefault = isDefault,
            updatedAtMillis = System.currentTimeMillis()
        )

        val savedId = if (id > 0L) {
            dao.update(entity)
            id
        } else {
            dao.insert(entity)
        }

        // Ensure at least one default always exists
        if (dao.getDefault() == null) {
            dao.markDefault(savedId)
        }
        return savedId
    }

    suspend fun setDefault(id: Long) {
        dao.clearAllDefaults()
        dao.markDefault(id)
    }

    suspend fun deleteTemplate(template: PromptTemplateEntity): Boolean {
        val all = dao.getAll()
        if (all.size <= 1) return false
        dao.delete(template)
        if (dao.getDefault() == null) {
            dao.getAll().firstOrNull()?.let { dao.markDefault(it.id) }
        }
        return true
    }

    suspend fun restoreMissingDefaults() {
        val existingNames = dao.getAll().map { it.name }.toSet()
        val missing = DefaultPromptTemplates.initialTemplates()
            .filterNot { it.name in existingNames }
            .map { it.copy(isDefault = false) }
        if (missing.isNotEmpty()) {
            dao.insertAll(missing)
        }
        if (dao.getDefault() == null) {
            dao.getAll().firstOrNull()?.let { dao.markDefault(it.id) }
        }
    }
}
