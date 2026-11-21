package com.tumorrecorder.app.domain.model

enum class ProcessingStatus(val label: String) {
    RECORDING("錄音中"),
    PENDING("待確認分析"),
    TRANSCRIBING("AI 轉錄與摘要中"),
    SENDING_EMAIL("寄送郵件中"),
    COMPLETED("已完成"),
    COMPLETED_NO_EMAIL("摘要完成（未寄信）"),
    FAILED("處理失敗");

    companion object {
        fun fromName(name: String): ProcessingStatus =
            entries.firstOrNull { it.name == name } ?: PENDING
    }
}

enum class CallDirection(val label: String) {
    INCOMING("來電"),
    OUTGOING("去電"),
    MANUAL("手動錄音"),
    IMPORTED("匯入音檔");

    companion object {
        fun fromName(name: String): CallDirection =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}
