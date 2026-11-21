package com.tumorrecorder.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val promptContent: String,
    val isDefault: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
