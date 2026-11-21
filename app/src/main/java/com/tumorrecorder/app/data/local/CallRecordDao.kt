package com.tumorrecorder.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {

    @Query("SELECT * FROM call_records ORDER BY startedAtMillis DESC")
    fun observeAll(): Flow<List<CallRecordEntity>>

    @Query(
        """
        SELECT * FROM call_records
        WHERE patientName LIKE '%' || :query || '%'
           OR patientIdentifier LIKE '%' || :query || '%'
           OR caseManagerName LIKE '%' || :query || '%'
           OR phoneNumber LIKE '%' || :query || '%'
           OR summary LIKE '%' || :query || '%'
           OR transcript LIKE '%' || :query || '%'
        ORDER BY startedAtMillis DESC
        """
    )
    fun search(query: String): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_records WHERE id = :id")
    fun observeById(id: Long): Flow<CallRecordEntity?>

    @Query("SELECT * FROM call_records WHERE id = :id")
    suspend fun getById(id: Long): CallRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CallRecordEntity): Long

    @Update
    suspend fun update(record: CallRecordEntity)

    @Delete
    suspend fun delete(record: CallRecordEntity)

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
