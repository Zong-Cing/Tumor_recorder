package com.tumorrecorder.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptTemplateDao {

    @Query("SELECT * FROM prompt_templates ORDER BY isDefault DESC, updatedAtMillis DESC")
    fun observeAll(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates ORDER BY isDefault DESC, updatedAtMillis DESC")
    suspend fun getAll(): List<PromptTemplateEntity>

    @Query("SELECT * FROM prompt_templates WHERE id = :id")
    suspend fun getById(id: Long): PromptTemplateEntity?

    @Query("SELECT * FROM prompt_templates WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): PromptTemplateEntity?

    @Query("SELECT COUNT(*) FROM prompt_templates")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: PromptTemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(templates: List<PromptTemplateEntity>)

    @Update
    suspend fun update(template: PromptTemplateEntity)

    @Delete
    suspend fun delete(template: PromptTemplateEntity)

    @Query("UPDATE prompt_templates SET isDefault = 0")
    suspend fun clearAllDefaults()

    @Query("UPDATE prompt_templates SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)
}
