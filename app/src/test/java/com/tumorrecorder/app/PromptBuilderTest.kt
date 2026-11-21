package com.tumorrecorder.app

import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.PromptBuilder
import com.tumorrecorder.app.domain.model.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun buildOncologyCallPrompt_includesRequiredClinicalFieldsAndCallerIdentityCheck() {
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
        assertTrue(prompt.contains("transcript"))
        assertTrue(prompt.contains("某醫院腫瘤個管中心"))
        assertTrue(prompt.contains("請特別註記目前化療週期"))
        assertTrue(prompt.contains("02-23456789"))
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
}
