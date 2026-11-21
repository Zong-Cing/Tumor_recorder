package com.tumorrecorder.app

import com.tumorrecorder.app.data.remote.GeminiResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiResponseParserTest {

    @Test
    fun parseSummaryResponse_parsesStructuredOncologyJson() {
        val sampleResponse = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"patientName\":\"王大明\",\"patientIdentifier\":\"A123456789\",\"identifierType\":\"身分證字號\",\"caseManagerName\":\"林佩蓉\",\"callerRelation\":\"家屬（女兒）\",\"inquiryCategory\":\"治療副作用／症狀處理\",\"urgencyLevel\":\"緊急\",\"summary\":\"• 病人化療後第三天發燒至 38.4 度\\n• 個管師建議立即前往急診抽血評估白血球\",\"actionItems\":[\"追蹤急診就醫情形\",\"通知林佩蓉個管師\"],\"transcript\":\"[00:02] 個管師：您好，腫瘤個管中心。\\n[00:05] 來電者：我爸爸王大明化療後發燒 38.4 度...\"}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = GeminiResponseParser.parseSummaryResponse(sampleResponse)

        assertEquals("王大明", parsed.patientName)
        assertEquals("A123456789", parsed.patientIdentifier)
        assertEquals("身分證字號", parsed.identifierType)
        assertEquals("林佩蓉", parsed.caseManagerName)
        assertEquals("緊急", parsed.urgencyLevel)
        assertEquals(2, parsed.actionItems.size)
        assertTrue(parsed.transcript.contains("王大明"))
    }

    @Test
    fun parseSummaryResponse_stripsMarkdownCodeFence() {
        val fenced = "```json\n{\"patientName\":\"陳小美\",\"patientIdentifier\":\"88123456\",\"identifierType\":\"病歷號碼\",\"caseManagerName\":\"張個管師\",\"callerRelation\":\"病人本人\",\"inquiryCategory\":\"門診排程\",\"urgencyLevel\":\"一般\",\"summary\":\"詢問改掛下週二門診\",\"actionItems\":[],\"transcript\":\"對話逐字稿\"}\n```"
        val stripped = GeminiResponseParser.stripCodeFence(fenced)
        assertTrue(stripped.startsWith("{"))
        assertTrue(stripped.endsWith("}"))
    }

    @Test
    fun parseSummaryResponse_ignoresThinkingParts() {
        val responseWithThought = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "thought": true,
                    "text": "Let me analyze the audio carefully..."
                  },
                  {
                    "text": "{\"patientName\":\"李美華\",\"patientIdentifier\":\"B223456789\",\"identifierType\":\"身分證字號\",\"caseManagerName\":\"黃個管師\",\"callerRelation\":\"病人本人\",\"inquiryCategory\":\"用藥諮詢\",\"urgencyLevel\":\"一般\",\"summary\":\"【來電者身分】病人本人\\n• 詢問標靶藥物漏吃處理\",\"actionItems\":[],\"transcript\":\"[00:01] 個管師：您好\"}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = GeminiResponseParser.parseSummaryResponse(responseWithThought)
        assertEquals("李美華", parsed.patientName)
        assertEquals("病人本人", parsed.callerRelation)
        assertEquals("黃個管師", parsed.caseManagerName)
    }
}
