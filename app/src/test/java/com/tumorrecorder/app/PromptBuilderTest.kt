package com.tumorrecorder.app

import com.tumorrecorder.app.domain.PromptBuilder
import com.tumorrecorder.app.domain.model.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun buildOncologyCallPrompt_includesRequiredClinicalFieldsAndCustomAppendix() {
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
        assertTrue(prompt.contains("transcript"))
        assertTrue(prompt.contains("某醫院腫瘤個管中心"))
        assertTrue(prompt.contains("請特別註記目前化療週期"))
        assertTrue(prompt.contains("02-23456789"))
    }
}
