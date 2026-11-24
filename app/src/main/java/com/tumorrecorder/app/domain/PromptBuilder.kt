package com.tumorrecorder.app.domain

import com.tumorrecorder.app.data.local.PromptTemplateEntity
import com.tumorrecorder.app.domain.model.AppSettings

/**
 * Builds the prompt for Gemini to transcribe and extract
 * oncology case management (腫瘤個案管理師) call details in Traditional Chinese,
 * supporting user-editable prompt templates, caller identity, covering/substitute role,
 * and optional post-call supplemental voice memos.
 */
object PromptBuilder {

    fun buildFromTemplate(
        template: PromptTemplateEntity?,
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings,
        hasSupplementAudio: Boolean = false,
        includeTranscript: Boolean = settings.generateTranscriptByDefault
    ): String {
        if (template == null || template.promptContent.isBlank()) {
            return buildOncologyCallPrompt(
                phoneNumber = phoneNumber,
                callTimeDisplay = callTimeDisplay,
                settings = settings,
                hasSupplementAudio = hasSupplementAudio,
                includeTranscript = includeTranscript
            )
        }

        val callerLine = phoneNumber?.takeIf { it.isNotBlank() } ?: "未知號碼"
        val unitLine = settings.defaultCaseManagerUnit.ifBlank { "腫瘤個案管理中心" }
        val customSection = settings.customPromptAppendix
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "\n\n【全域額外指示】\n$it" }
            ?: ""

        val supplementSection = if (hasSupplementAudio) {
            val transcriptMergeHint = if (includeTranscript) {
                "；並在 transcript 欄位明確分段標示【原始通話逐字稿】與【個管師口述補充】。"
            } else {
                "（本次為省費精簡模式，不需產出逐字稿）。"
            }
            """

【雙音檔整合說明（原始通話 ＋ 通話後口述補充）】
本次同時提供兩段音檔：
1. 「原始通話錄音」
2. 「個管師通話結束後的口述補充摘要」
請將「口述補充摘要」視為最高優先權的事實核對依據（例如病人姓名、病歷號、來電者身分、是否為代班接聽與原負責個管師、症狀細節、臨床判斷與交班待辦），與原始通話整合產出完整摘要$transcriptMergeHint
            """.trimIndent()
        } else {
            ""
        }

        val resolvedBody = template.promptContent
            .replace("{{unitName}}", unitLine)
            .replace("{{phoneNumber}}", callerLine)
            .replace("{{callTime}}", callTimeDisplay)
            .trim()

        val transcriptFieldLine = if (includeTranscript) {
            "\n- transcript：完整對話逐字稿，依序標記說話者（若有口述補充請分段標示【原始通話逐字稿】與【個管師口述補充】），模糊處標記「（音訊模糊）」"
        } else {
            "\n※ 本次為省費精簡模式，不需輸出完整逐字稿，僅產出上述結構化欄位與重點摘要即可。"
        }

        return """
$resolvedBody$supplementSection

【通話背景資訊】
- 使用分析範本：${template.name}
- 通話時間：$callTimeDisplay
- 來電／去電號碼：$callerLine

【輸出格式要求（必須嚴格遵守 JSON Schema）】
請一律以繁體中文輸出合法 JSON，並填寫以下欄位：
- patientName：病人姓名（未提及請填「未提及」）
- patientIdentifier：病歷號碼或身分證字號（若兩者皆有請並列；未提及填「未提及」）
- identifierType：病歷號碼／身分證字號／兩者皆有／未提及
- caseManagerName：請區分接聽角色並填寫所屬個管師，例如「林○○（負責個管師本人）」或「代班接聽（原負責：王○○）」（若僅提及個管師姓名則填姓名；完全未提及填「未提及」）
- callerRelation：務必分析來電者身分，明確標註「病人本人」、「家屬（註明關係，例如女兒、配偶、兒子）」或「院內同仁」（無法判斷才填「未提及」）
- inquiryCategory：主要諮詢分類
- urgencyLevel：僅能填寫「一般」、「需追蹤」或「緊急」
- summary：開頭第一行務必先註記「【來電者身分】... ｜【接聽角色】負責個管師本人 / 代班接聽（原負責：XXX）」，接著依上述指示產出條列式重點摘要（禁止捏造未提及之數值或醫囑）
- actionItems：後續待辦事項字串陣列（若為代班接聽，請明確列入「轉交／交班給原負責個管師跟進」；無則回傳空陣列）$transcriptFieldLine$customSection
        """.trimIndent()
    }

    fun buildOncologyCallPrompt(
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings,
        hasSupplementAudio: Boolean = false,
        includeTranscript: Boolean = settings.generateTranscriptByDefault
    ): String {
        val defaultTemplate = DefaultPromptTemplates.initialTemplates().first()
        return buildFromTemplate(
            template = defaultTemplate,
            phoneNumber = phoneNumber,
            callTimeDisplay = callTimeDisplay,
            settings = settings,
            hasSupplementAudio = hasSupplementAudio,
            includeTranscript = includeTranscript
        )
    }

    fun buildTranscriptOnlyPrompt(
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings,
        hasSupplementAudio: Boolean = false
    ): String {
        val callerLine = phoneNumber?.takeIf { it.isNotBlank() } ?: "未知號碼"
        val unitLine = settings.defaultCaseManagerUnit.ifBlank { "腫瘤個案管理中心" }
        val dualSection = if (hasSupplementAudio) {
            """

【雙音檔分段要求】
本次提供兩段音檔，請務必清楚分段輸出：
1. 先輸出「【原始通話逐字稿】」
2. 再輸出「【個管師口述補充】」
            """.trimIndent()
        } else {
            ""
        }

        return """
你是台灣醫院「$unitLine」的專業醫療語音逐字稿聽打助手。
請將提供的通話錄音忠實轉錄為繁體中文（台灣慣用醫療用語）完整逐字稿。

【通話資訊】
- 通話時間：$callTimeDisplay
- 號碼：$callerLine$dualSection

【轉錄規則】
1. 依時間順序忠實記錄對話內容，盡可能加上概略時間標記（如 [00:05]）並區分說話者（如「個管師：」、「來電者：」）。
2. 若因單端收音僅清楚錄到個管師端，請忠實轉錄個管師說的內容，對方聽不清處標記「（對方聲音微弱／音訊模糊）」，切勿自行捏造對話。
3. 特別注意病人姓名、病歷號碼、身分證字號、癌別、化療／標靶藥物名稱、劑量、體溫與回診日期之準確性。
4. 請直接輸出純文字逐字稿內容，不要輸出 JSON、Markdown 程式碼區塊或額外開場白。
        """.trimIndent()
    }
}
