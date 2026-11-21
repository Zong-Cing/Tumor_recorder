package com.tumorrecorder.app.domain

import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.model.AppSettings

/**
 * Builds the prompt for Gemini to transcribe and extract
 * oncology case management (腫瘤個案管理師) call details in Traditional Chinese,
 * supporting user-editable prompt templates.
 */
object PromptBuilder {

    fun buildFromTemplate(
        template: PromptTemplateEntity?,
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings
    ): String {
        if (template == null || template.promptContent.isBlank()) {
            return buildOncologyCallPrompt(phoneNumber, callTimeDisplay, settings)
        }

        val callerLine = phoneNumber?.takeIf { it.isNotBlank() } ?: "未知號碼"
        val unitLine = settings.defaultCaseManagerUnit.ifBlank { "腫瘤個案管理中心" }
        val customSection = settings.customPromptAppendix
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "\n\n【全域額外指示】\n$it" }
            ?: ""

        val resolvedBody = template.promptContent
            .replace("{{unitName}}", unitLine)
            .replace("{{phoneNumber}}", callerLine)
            .replace("{{callTime}}", callTimeDisplay)
            .trim()

        return """
$resolvedBody

【通話背景資訊】
- 使用分析範本：${template.name}
- 通話時間：$callTimeDisplay
- 來電／去電號碼：$callerLine

【輸出格式要求（必須嚴格遵守 JSON Schema）】
請一律以繁體中文輸出合法 JSON，並填寫以下欄位：
- patientName：病人姓名（未提及請填「未提及」）
- patientIdentifier：病歷號碼或身分證字號（若兩者皆有請並列；未提及填「未提及」）
- identifierType：病歷號碼／身分證字號／兩者皆有／未提及
- caseManagerName：所屬個管師姓名（未提及請填「未提及」）
- callerRelation：務必分析來電者身分，明確標註「病人本人」、「家屬（註明關係，例如女兒、配偶、兒子）」或「院內同仁」（無法判斷才填「未提及」）
- inquiryCategory：主要諮詢分類
- urgencyLevel：僅能填寫「一般」、「需追蹤」或「緊急」
- summary：開頭第一行務必先註記【來電者身分】（病人本人或家屬與關係），接著依上述指示產出條列式重點摘要（禁止捏造未提及之數值或醫囑）
- actionItems：後續待辦事項字串陣列（無則回傳空陣列）
- transcript：完整對話逐字稿，依序標記說話者（例如「[00:05] 個管師：...」、「[00:12] 來電者：...」），模糊處標記「（音訊模糊）」$customSection
        """.trimIndent()
    }

    fun buildOncologyCallPrompt(
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings
    ): String {
        val defaultTemplate = DefaultPromptTemplates.initialTemplates().first()
        return buildFromTemplate(
            template = defaultTemplate,
            phoneNumber = phoneNumber,
            callTimeDisplay = callTimeDisplay,
            settings = settings
        )
    }
}
