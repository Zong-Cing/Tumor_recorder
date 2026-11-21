package com.tumorrecorder.app.domain

import com.tumorrecorder.app.domain.model.AppSettings

/**
 * Builds the system/user prompt for Gemini to transcribe and extract
 * oncology case management (腫瘤個案管理師) call details accurately in Traditional Chinese.
 */
object PromptBuilder {

    fun buildOncologyCallPrompt(
        phoneNumber: String?,
        callTimeDisplay: String,
        settings: AppSettings
    ): String {
        val callerLine = phoneNumber?.takeIf { it.isNotBlank() } ?: "未知號碼"
        val unitLine = settings.defaultCaseManagerUnit.ifBlank { "腫瘤個案管理中心" }
        val customSection = settings.customPromptAppendix
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "\n\n【額外指定指示】\n$it" }
            ?: ""

        return """
你是一位專業的台灣醫院「$unitLine」語音紀錄助理，協助腫瘤個案管理師整理來電與去電的醫療諮詢通話錄音。

【通話背景資訊】
- 通話時間：$callTimeDisplay
- 來電／去電號碼：$callerLine

【任務目標】
請仔細聆聽完整音檔（可能包含國語、台語夾雜或醫療專有名詞，如化療、標靶、放療、免疫治療、白血球低下、人工血管 Port-A、電腦斷層 CT、切片報告等），並以「繁體中文」產出嚴格符合 JSON Schema 的結構化結果：

1. patientName（病人姓名）：
   - 擷取通話中確認的病人真實姓名。
   - 若發音不確定，請在姓名後加註「（請核對）」。若通話未提及，填「未提及」。
2. patientIdentifier（病歷號碼或身分證字號）：
   - 精確擷取通話中報出的「病歷號碼」或「身分證字號」（注意英文字母與數字長度，例如身分證為 1 碼英文 + 9 碼數字）。
   - 若兩者皆有提及，請以「病歷號：XXX / 身分證：YYY」格式並列。若未提及，填「未提及」。
3. identifierType（證件類型）：
   - 填寫「病歷號碼」、「身分證字號」、「兩者皆有」或「未提及」。
4. caseManagerName（所屬個管師姓名）：
   - 擷取該病人所屬的腫瘤個案管理師姓名（例如「林小姐」、「陳個管師」）。若未提及，填「未提及」。
5. callerRelation（來電者身分）：
   - 判斷來電者為「病人本人」、「家屬（註明關係）」、「院內同仁」或「未提及」。
6. inquiryCategory（諮詢分類）：
   - 歸納主要來電目的，例如：「治療副作用／症狀處理」、「用藥諮詢」、「門診／檢查／住院排程」、「檢驗報告查詢」、「急診／緊急狀況」、「診斷書／重大傷病等行政文件」、「心理支持與衛教」、「其他」。
7. urgencyLevel（緊急程度）：
   - 僅能填寫以下三者之一：「一般」、「需追蹤」、「緊急」。
   - 若出現發燒（>=38°C，尤其化療期間）、呼吸困難、嚴重出血、意識改變、無法進食或劇烈疼痛等警訊，務必標記為「緊急」。
8. summary（通話重點摘要）：
   - 以清晰條列式整理：來電詢問緣由、目前症狀／治療階段、個管師給予的衛教與回覆重點、討論結論。
   - 禁止捏造錄音中未出現的醫療數值或醫囑。
9. actionItems（後續待辦事項陣列）：
   - 列出明確後續動作（例如：「轉告林個管師回電」、「協助確認下週三門診時間」、「提醒病人若體溫超過 38 度立即至急診」）。若無則回傳空陣列。
10. transcript（完整逐字稿）：
    - 盡可能完整還原整段對話，依序標記說話者（例如「[00:05] 個管師：...」、「[00:12] 來電者：...」）。
    - 若某段因雜訊或對方音量過小無法聽清，請標記「（音訊模糊）」，不可自行臆測編造。

請只輸出合法 JSON，不要包含 Markdown 程式碼區塊以外的多餘說明。$customSection
        """.trimIndent()
    }
}
