package com.tumorrecorder.app

import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.data.remote.EmailFormatter
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.CallDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailFormatterTest {

    private val sampleRecord = CallRecordEntity(
        id = 1L,
        phoneNumber = "0912345678",
        direction = CallDirection.INCOMING.name,
        startedAtMillis = 1763712000000L,
        durationSeconds = 185L,
        patientName = "林志明",
        patientIdentifier = "12345678",
        identifierType = "病歷號碼",
        caseManagerName = "陳護理師",
        callerRelation = "病人本人",
        inquiryCategory = "治療副作用／症狀處理",
        urgencyLevel = "緊急",
        summary = "病人主訴體溫 > 38.2°C <注意>",
        actionItemsText = "立即至急診\n通知陳護理師",
        transcript = "[00:01] 個管師：您好，請問病人姓名與病歷號碼？\n[00:05] 來電者：我叫林志明，病歷號 12345678。"
    )

    @Test
    fun buildSubject_includesUrgencyPatientIdentifierAndCaseManager() {
        val subject = EmailFormatter.buildSubject(sampleRecord)
        assertTrue(subject.startsWith("【緊急】[腫瘤個管通話紀錄]"))
        assertTrue(subject.contains("林志明"))
        assertTrue(subject.contains("12345678"))
        assertTrue(subject.contains("陳護理師"))
    }

    @Test
    fun buildHtmlBody_escapesSpecialCharsAndIncludesTranscriptAndSummary() {
        val html = EmailFormatter.buildHtmlBody(sampleRecord, AppSettings())
        assertTrue(html.contains("林志明"))
        assertTrue(html.contains("12345678"))
        assertTrue(html.contains("陳護理師"))
        assertTrue(html.contains("&gt; 38.2°C &lt;注意&gt;"))
        assertFalse(html.contains("<注意>"))
        assertTrue(html.contains("完整通話逐字稿"))
    }

    @Test
    fun buildPlainTextBody_containsAllRequiredCaseManagerSections() {
        val body = EmailFormatter.buildPlainTextBody(sampleRecord, AppSettings())
        assertTrue(body.contains("病人姓名：林志明"))
        assertTrue(body.contains("病歷號／身分證字號：12345678"))
        assertTrue(body.contains("所屬個管師：陳護理師"))
        assertTrue(body.contains("【通話討論摘要】"))
        assertTrue(body.contains("【完整通話逐字稿】"))
    }
}
