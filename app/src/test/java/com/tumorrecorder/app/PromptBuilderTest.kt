package com.tumorrecorder.app

import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.PromptBuilder
import com.tumorrecorder.app.domain.model.AppSettings
import com.tumorrecorder.app.domain.model.GeminiThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun buildOncologyCallPrompt_defaultsToCompactModeWithoutTranscript() {
        val settings = AppSettings(
            defaultCaseManagerUnit = "某醫院腫瘤個管中心",
            customPromptAppendix = "請特別註記目前化療週期"
        )
        val prompt = PromptBuilder.buildOncologyCallPrompt(
            phoneNumber = "02-23456789",
            callTimeDisplay = "2026/11/21 14:30:00",
            settings = settings
        )

        assertTrue(prompt.contains("patientName"))
        assertTrue(prompt.contains("patientIdentifier"))
        assertTrue(prompt.contains("caseManagerName"))
        assertTrue(prompt.contains("callerRelation"))
        assertTrue(prompt.contains("病人本人"))
        assertTrue(prompt.contains("家屬"))
        assertFalse(prompt.contains("- transcript："))
        assertTrue(prompt.contains("不需輸出完整逐字稿"))
        assertTrue(prompt.contains("某醫院腫瘤個管中心"))
        assertTrue(prompt.contains("請特別註記目前化療週期"))
        assertTrue(prompt.contains("02-23456789"))
    }

    @Test
    fun buildOncologyCallPrompt_includesTranscriptFieldWhenEnabled() {
        val settings = AppSettings(defaultCaseManagerUnit = "某醫院腫瘤個管中心")
        val prompt = PromptBuilder.buildOncologyCallPrompt(
            phoneNumber = "02-23456789",
            callTimeDisplay = "2026/11/21 14:30:00",
            settings = settings,
            includeTranscript = true
        )

        assertTrue(prompt.contains("- transcript："))
    }

    @Test
    fun buildFromTemplate_substitutesDynamicPlaceholdersAndKeepsSchemaFooter() {
        val customTemplate = PromptTemplateEntity(
            id = 99L,
            name = "乳癌術後追蹤自訂範本",
            promptContent = "單位：{{unitName}}，號碼：{{phoneNumber}}，時間：{{callTime}}。請分析傷口與引流管狀況。"
        )
        val settings = AppSettings(defaultCaseManagerUnit = "乳癌個管團隊")
        val prompt = PromptBuilder.buildFromTemplate(
            template = customTemplate,
            phoneNumber = "0988111222",
            callTimeDisplay = "2026/11/21 16:00:00",
            settings = settings
        )

        assertTrue(prompt.contains("單位：乳癌個管團隊"))
        assertTrue(prompt.contains("號碼：0988111222"))
        assertTrue(prompt.contains("時間：2026/11/21 16:00:00"))
        assertTrue(prompt.contains("請分析傷口與引流管狀況"))
        assertTrue(prompt.contains("使用分析範本：乳癌術後追蹤自訂範本"))
        assertTrue(prompt.contains("JSON Schema"))
    }

    @Test
    fun buildFromTemplate_includesDualAudioAndSubstituteCaseManagerInstructions() {
        val template = com.tumorrecorder.app.domain.DefaultPromptTemplates.initialTemplates().first()
        val settings = AppSettings(defaultCaseManagerUnit = "腫瘤個管中心")

        val prompt = PromptBuilder.buildFromTemplate(
            template = template,
            phoneNumber = "0912345678",
            callTimeDisplay = "2026/11/22 09:30:00",
            settings = settings,
            hasSupplementAudio = true,
            includeTranscript = true
        )

        assertTrue(prompt.contains("單端收音"))
        assertTrue(prompt.contains("代班接聽"))
        assertTrue(prompt.contains("負責個管師本人"))
        assertTrue(prompt.contains("雙音檔整合說明"))
        assertTrue(prompt.contains("通話後口述補充"))
        assertTrue(prompt.contains("【個管師口述補充】"))
    }

    @Test
    fun buildTranscriptOnlyPrompt_outputsVerbatimRulesAndDualAudioMarkers() {
        val settings = AppSettings(defaultCaseManagerUnit = "腫瘤個管中心")
        val prompt = PromptBuilder.buildTranscriptOnlyPrompt(
            phoneNumber = "0912345678",
            callTimeDisplay = "2026/11/23 15:00:00",
            settings = settings,
            hasSupplementAudio = true
        )

        assertTrue(prompt.contains("完整逐字稿"))
        assertTrue(prompt.contains("【原始通話逐字稿】"))
        assertTrue(prompt.contains("【個管師口述補充】"))
        assertTrue(prompt.contains("不要輸出 JSON"))
    }

    @Test
    fun geminiThinkingLevel_resolvesSafeApiValuesPerModel() {
        assertEquals("LOW", GeminiThinkingLevel.AUTO_LOWEST.resolveApiThinkingLevel("gemini-4-hydrogen"))
        assertEquals("LOW", GeminiThinkingLevel.AUTO_LOWEST.resolveApiThinkingLevel("gemini-4-carbon"))
        assertEquals("LOW", GeminiThinkingLevel.AUTO_LOWEST.resolveApiThinkingLevel("gemini-3.8-flash"))
        assertEquals("MINIMAL", GeminiThinkingLevel.AUTO_LOWEST.resolveApiThinkingLevel("gemini-3.6-flash"))
        assertEquals("MINIMAL", GeminiThinkingLevel.AUTO_LOWEST.resolveApiThinkingLevel("gemini-3.5-flash-lite"))
        assertNull(GeminiThinkingLevel.AUTO_LOWEST.resolveApiThinkingLevel("gemini-2.5-flash"))

        assertEquals("LOW", GeminiThinkingLevel.LOW.resolveApiThinkingLevel("gemini-4-hydrogen"))
        assertEquals("MEDIUM", GeminiThinkingLevel.MEDIUM.resolveApiThinkingLevel("gemini-4-hydrogen"))
        assertEquals("HIGH", GeminiThinkingLevel.HIGH.resolveApiThinkingLevel("gemini-4-hydrogen"))
    }
}
