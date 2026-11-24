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

    @Test
    fun parseSummaryResponse_handlesOmittedTranscriptField() {
        val responseWithoutTranscript = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"patientName\":\"張阿嬤\",\"patientIdentifier\":\"99887766\",\"identifierType\":\"病歷號碼\",\"caseManagerName\":\"林佩蓉（負責個管師本人）\",\"callerRelation\":\"家屬（兒子）\",\"inquiryCategory\":\"門診排程\",\"urgencyLevel\":\"一般\",\"summary\":\"【來電者身分】家屬（兒子） ｜【接聽角色】負責個管師本人\\n• 確認下週三門診時間\",\"actionItems\":[\"協助查詢抽血單\"]}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = GeminiResponseParser.parseSummaryResponse(responseWithoutTranscript)
        assertEquals("張阿嬤", parsed.patientName)
        assertEquals("99887766", parsed.patientIdentifier)
        assertEquals("", parsed.transcript)
        assertEquals(1, parsed.actionItems.size)
    }

    @Test
    fun flexBackoffPolicy_followsExponentialDelaysAndStopsAt30Minutes() {
        val policy = com.tumorrecorder.app.data.remote.FlexBackoffPolicy
        assertEquals(30_000L, policy.nextDelayMillis(attempt = 0, elapsedMillis = 0L))
        assertEquals(60_000L, policy.nextDelayMillis(attempt = 1, elapsedMillis = 35_000L))
        assertEquals(120_000L, policy.nextDelayMillis(attempt = 2, elapsedMillis = 100_000L))
        assertEquals(240_000L, policy.nextDelayMillis(attempt = 3, elapsedMillis = 220_000L))
        assertEquals(480_000L, policy.nextDelayMillis(attempt = 4, elapsedMillis = 460_000L))
        assertEquals(600_000L, policy.nextDelayMillis(attempt = 5, elapsedMillis = 940_000L))

        // Caps to remaining window before 30 min
        assertEquals(90_000L, policy.nextDelayMillis(attempt = 5, elapsedMillis = policy.MAX_FLEX_WAIT_MILLIS - 90_000L))

        // Stops retrying and switches to Standard once < 30s left or >= 30 min elapsed
        org.junit.Assert.assertNull(policy.nextDelayMillis(attempt = 5, elapsedMillis = policy.MAX_FLEX_WAIT_MILLIS - 20_000L))
        org.junit.Assert.assertNull(policy.nextDelayMillis(attempt = 6, elapsedMillis = policy.MAX_FLEX_WAIT_MILLIS))

        assertEquals("30 秒", policy.formatDelay(30_000L))
        assertEquals("2 分鐘", policy.formatDelay(120_000L))
        assertEquals("1 分 30 秒", policy.formatDelay(90_000L))
    }
}
