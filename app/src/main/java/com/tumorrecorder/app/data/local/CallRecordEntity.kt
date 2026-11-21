package com.tumorrecorder.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tumorrecorder.app.domain.model.CallDirection
import com.tumorrecorder.app.domain.model.ProcessingStatus

@Entity(tableName = "call_records")
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val phoneNumber: String = "",
    val direction: String = CallDirection.MANUAL.name,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0L,
    val audioFilePath: String = "",
    val audioMimeType: String = "audio/mp4",
    val status: String = ProcessingStatus.PENDING.name,
    // Oncology case management structured fields
    val patientName: String = "",
    val patientIdentifier: String = "",
    val identifierType: String = "",
    val caseManagerName: String = "",
    val callerRelation: String = "",
    val inquiryCategory: String = "",
    val urgencyLevel: String = "一般",
    val summary: String = "",
    val actionItemsText: String = "",
    val transcript: String = "",
    val promptTemplateName: String = "",
    // Email tracking
    val emailSent: Boolean = false,
    val emailSentAtMillis: Long? = null,
    val emailRecipientSnapshot: String = "",
    val errorMessage: String? = null
) {
    val processingStatus: ProcessingStatus
        get() = ProcessingStatus.fromName(status)

    val callDirection: CallDirection
        get() = CallDirection.fromName(direction)

    fun actionItemsList(): List<String> =
        actionItemsText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
