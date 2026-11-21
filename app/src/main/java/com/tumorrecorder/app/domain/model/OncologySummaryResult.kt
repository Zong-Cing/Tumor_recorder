package com.tumorrecorder.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Structured extraction tailored for oncology case manager (腫瘤個案管理師) calls.
 */
@Serializable
data class OncologySummaryResult(
    val patientName: String = "未提及",
    val patientIdentifier: String = "未提及",
    val identifierType: String = "未提及",
    val caseManagerName: String = "未提及",
    val callerRelation: String = "未提及",
    val inquiryCategory: String = "一般諮詢",
    val urgencyLevel: String = "一般",
    val summary: String = "",
    val actionItems: List<String> = emptyList(),
    val transcript: String = ""
)
