package com.tumorrecorder.app.data.remote

import com.tumorrecorder.app.data.local.CallRecordEntity
import com.tumorrecorder.app.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.activation.CommandMap
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.activation.MailcapCommandMap
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

class EmailService {

    suspend fun sendCallSummaryEmail(
        record: CallRecordEntity,
        settings: AppSettings
    ) = withContext(Dispatchers.IO) {
        val recipients = settings.parsedRecipients()
        require(settings.senderEmail.isNotBlank()) { "尚未設定寄件者 Gmail 帳號" }
        require(settings.senderAppPassword.isNotBlank()) { "尚未設定 Gmail 應用程式密碼" }
        require(recipients.isNotEmpty()) { "尚未設定有效的收件人 Email" }

        ensureAndroidMailcapRegistered()

        val session = buildMailSession(settings)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.senderEmail.trim(), settings.defaultCaseManagerUnit, "UTF-8"))
            recipients.forEach { addr ->
                addRecipient(Message.RecipientType.TO, InternetAddress(addr))
            }
            setSubject(EmailFormatter.buildSubject(record), "UTF-8")
            sentDate = Date()
        }

        val alternativePart = MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply {
                setText(EmailFormatter.buildPlainTextBody(record, settings), "UTF-8")
            })
            addBodyPart(MimeBodyPart().apply {
                setContent(EmailFormatter.buildHtmlBody(record, settings), "text/html; charset=UTF-8")
            })
        }

        val audioFile = record.audioFilePath.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
        val supplementFile = record.supplementAudioFilePath.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
        if (settings.attachAudioToEmail && (audioFile != null || supplementFile != null)) {
            val mixed = MimeMultipart("mixed").apply {
                addBodyPart(MimeBodyPart().apply {
                    setContent(alternativePart)
                })
                audioFile?.let { file ->
                    addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(FileDataSource(file))
                        fileName = MimeUtility.encodeText(file.name, "UTF-8", null)
                    })
                }
                supplementFile?.let { file ->
                    addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(FileDataSource(file))
                        fileName = MimeUtility.encodeText(file.name, "UTF-8", null)
                    })
                }
            }
            message.setContent(mixed)
        } else {
            message.setContent(alternativePart)
        }

        sendMessageWithMailClassLoader(message)
    }

    suspend fun sendTestEmail(settings: AppSettings) = withContext(Dispatchers.IO) {
        val recipients = settings.parsedRecipients()
        require(settings.senderEmail.isNotBlank()) { "請先填寫寄件者 Gmail 帳號" }
        require(settings.senderAppPassword.isNotBlank()) { "請先填寫 Gmail 應用程式密碼" }
        require(recipients.isNotEmpty()) { "請先填寫收件人 Email" }

        ensureAndroidMailcapRegistered()

        val session = buildMailSession(settings)
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.senderEmail.trim(), settings.defaultCaseManagerUnit, "UTF-8"))
            recipients.forEach { addr ->
                addRecipient(Message.RecipientType.TO, InternetAddress(addr))
            }
            setSubject("[腫瘤個管通話助理] 測試信件連線成功", "UTF-8")
            sentDate = Date()
            val alternative = MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply {
                    setText(
                        """
                        您好：

                        這是由「腫瘤個管通話助理」App 發出的 SMTP 測試信件。
                        收到此信代表您的 Gmail 應用程式密碼與收件設定均已正確配置。

                        單位名稱：${settings.defaultCaseManagerUnit}
                        寄出時間：${EmailFormatter.formatTimestamp(System.currentTimeMillis())}
                        """.trimIndent(),
                        "UTF-8"
                    )
                })
                addBodyPart(MimeBodyPart().apply {
                    setContent(
                        "<p>您好：</p><p>這是由「腫瘤個管通話助理」App 發出的 HTML 測試信件，代表 Gmail SMTP 與多格式郵件設定均已正常運作。</p><p>單位名稱：${EmailFormatter.escapeHtml(settings.defaultCaseManagerUnit)}<br/>寄出時間：${EmailFormatter.formatTimestamp(System.currentTimeMillis())}</p>",
                        "text/html; charset=UTF-8"
                    )
                })
            }
            setContent(alternative)
        }
        sendMessageWithMailClassLoader(message)
    }

    private fun sendMessageWithMailClassLoader(message: MimeMessage) {
        val prev = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = MimeMessage::class.java.classLoader
            Transport.send(message)
        } finally {
            Thread.currentThread().contextClassLoader = prev
        }
    }

    private fun ensureAndroidMailcapRegistered() {
        val mc = (CommandMap.getDefaultCommandMap() as? MailcapCommandMap) ?: MailcapCommandMap()
        mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
        mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
        mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
        mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
        mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
        CommandMap.setDefaultCommandMap(mc)
    }

    private fun buildMailSession(settings: AppSettings): Session {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.host", settings.smtpHost.ifBlank { AppSettings.DEFAULT_SMTP_HOST })
            put("mail.smtp.port", settings.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
            if (settings.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.port", "465")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }

        val user = settings.senderEmail.trim()
        val pass = settings.senderAppPassword.replace(" ", "").trim()

        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(user, pass)
            }
        })
    }
}

/**
 * Pure email subject and HTML/PlainText body formatter for oncology call summaries.
 */
object EmailFormatter {

    internal const val NO_TRANSCRIPT_PLACEHOLDER =
        "（本次為精簡摘要模式，未產出完整逐字稿；如需逐字稿可於 App 紀錄詳情頁隨時點擊補產）"

    fun buildSubject(record: CallRecordEntity): String {
        val urgencyPrefix = if (record.urgencyLevel == "緊急") "【緊急】" else ""
        val patient = record.patientName.ifBlank { "未識別病人" }
        val identifier = record.patientIdentifier.takeIf { it.isNotBlank() && it != "未提及" }
            ?.let { " ($it)" }
            ?: ""
        val cm = record.caseManagerName.takeIf { it.isNotBlank() && it != "未提及" }
            ?.let { " - 個管師:$it" }
            ?: ""
        val time = formatShortTimestamp(record.startedAtMillis)
        return "$urgencyPrefix[腫瘤個管通話紀錄] $patient$identifier$cm - $time"
    }

    fun buildPlainTextBody(record: CallRecordEntity, settings: AppSettings): String {
        val actionItems = record.actionItemsList()
        val actionSection = if (actionItems.isEmpty()) {
            "無特別待辦事項"
        } else {
            actionItems.mapIndexed { idx, item -> "${idx + 1}. $item" }.joinToString("\n")
        }

        return buildString {
            appendLine("==========================================")
            appendLine("${settings.defaultCaseManagerUnit} - 通話諮詢紀錄")
            appendLine("==========================================")
            appendLine("病人姓名：${record.patientName.ifBlank { "未提及" }}")
            appendLine("病歷號／身分證字號：${record.patientIdentifier.ifBlank { "未提及" }} (${record.identifierType.ifBlank { "未提及" }})")
            appendLine("所屬個管師：${record.caseManagerName.ifBlank { "未提及" }}")
            appendLine("來電者身分：${record.callerRelation.ifBlank { "未提及" }}")
            appendLine("諮詢分類：${record.inquiryCategory.ifBlank { "一般諮詢" }}")
            appendLine("緊急程度：${record.urgencyLevel.ifBlank { "一般" }}")
            appendLine("通話類型：${record.callDirection.label} / 號碼：${record.phoneNumber.ifBlank { "未知" }}")
            appendLine("通話時間：${formatTimestamp(record.startedAtMillis)}（時長 ${formatDuration(record.durationSeconds)}）")
            if (record.promptTemplateName.isNotBlank()) {
                appendLine("使用分析範本：${record.promptTemplateName}")
            }
            appendLine()
            appendLine("【通話討論摘要】")
            appendLine(record.summary.ifBlank { "（無摘要內容）" })
            appendLine()
            appendLine("【後續待辦／追蹤事項】")
            appendLine(actionSection)
            appendLine()
            appendLine("【完整通話逐字稿】")
            appendLine(record.transcript.ifBlank { NO_TRANSCRIPT_PLACEHOLDER })
            appendLine()
            appendLine("------------------------------------------")
            appendLine("※ 本紀錄由 AI 自動轉錄整理，請個管師核對病人姓名、病歷號與醫囑細節後再納入正式醫療紀錄。")
        }
    }

    fun buildHtmlBody(record: CallRecordEntity, settings: AppSettings): String {
        val urgencyColor = when (record.urgencyLevel) {
            "緊急" -> "#DC2626"
            "需追蹤" -> "#D97706"
            else -> "#0F766E"
        }
        val actionItemsHtml = record.actionItemsList().let { items ->
            if (items.isEmpty()) {
                "<p style=\"color:#64748B;margin:0;\">無特別待辦事項</p>"
            } else {
                "<ul style=\"margin:0;padding-left:20px;\">" +
                    items.joinToString("") { "<li style=\"margin-bottom:4px;\">${escapeHtml(it)}</li>" } +
                    "</ul>"
            }
        }
        val summaryHtml = escapeHtml(record.summary.ifBlank { "（無摘要內容）" }).replace("\n", "<br/>")
        val transcriptHtml = escapeHtml(record.transcript.ifBlank { NO_TRANSCRIPT_PLACEHOLDER }).replace("\n", "<br/>")
        val headerTitle = if (record.transcript.isBlank()) "腫瘤個案管理通話摘要" else "腫瘤個案管理通話摘要與逐字稿"

        return """
<!DOCTYPE html>
<html lang="zh-Hant">
<head><meta charset="UTF-8" /></head>
<body style="font-family:-apple-system,BlinkMacSystemFont,'Noto Sans TC','Microsoft JhengHei',sans-serif;background:#F8FAFC;color:#0F172A;margin:0;padding:16px;">
  <div style="max-width:680px;margin:0 auto;background:#FFFFFF;border-radius:12px;border:1px solid #E2E8F0;overflow:hidden;">
    <div style="background:#0F766E;color:#FFFFFF;padding:18px 24px;">
      <div style="font-size:13px;opacity:0.9;">${escapeHtml(settings.defaultCaseManagerUnit)}</div>
      <h2 style="margin:4px 0 0 0;font-size:20px;">$headerTitle</h2>
    </div>

    <div style="padding:20px 24px;">
      <div style="margin-bottom:16px;">
        <span style="display:inline-block;background:$urgencyColor;color:#FFFFFF;padding:4px 12px;border-radius:999px;font-size:12px;font-weight:700;">
          緊急程度：${escapeHtml(record.urgencyLevel.ifBlank { "一般" })}
        </span>
        <span style="display:inline-block;background:#F1F5F9;color:#334155;padding:4px 12px;border-radius:999px;font-size:12px;margin-left:8px;">
          ${escapeHtml(record.inquiryCategory.ifBlank { "一般諮詢" })}
        </span>
      </div>

      <table style="width:100%;border-collapse:collapse;margin-bottom:20px;font-size:14px;">
        <tr>
          <td style="padding:8px 12px;background:#F8FAFC;border:1px solid #E2E8F0;width:32%;font-weight:600;">病人姓名</td>
          <td style="padding:8px 12px;border:1px solid #E2E8F0;">${escapeHtml(record.patientName.ifBlank { "未提及" })}</td>
        </tr>
        <tr>
          <td style="padding:8px 12px;background:#F8FAFC;border:1px solid #E2E8F0;font-weight:600;">病歷號／身分證字號</td>
          <td style="padding:8px 12px;border:1px solid #E2E8F0;">${escapeHtml(record.patientIdentifier.ifBlank { "未提及" })} <span style="color:#64748B;">(${escapeHtml(record.identifierType.ifBlank { "未提及" })})</span></td>
        </tr>
        <tr>
          <td style="padding:8px 12px;background:#F8FAFC;border:1px solid #E2E8F0;font-weight:600;">所屬個管師</td>
          <td style="padding:8px 12px;border:1px solid #E2E8F0;">${escapeHtml(record.caseManagerName.ifBlank { "未提及" })}</td>
        </tr>
        <tr>
          <td style="padding:8px 12px;background:#F8FAFC;border:1px solid #E2E8F0;font-weight:600;">來電者身分</td>
          <td style="padding:8px 12px;border:1px solid #E2E8F0;">${escapeHtml(record.callerRelation.ifBlank { "未提及" })}</td>
        </tr>
        <tr>
          <td style="padding:8px 12px;background:#F8FAFC;border:1px solid #E2E8F0;font-weight:600;">通話號碼／類型</td>
          <td style="padding:8px 12px;border:1px solid #E2E8F0;">${escapeHtml(record.phoneNumber.ifBlank { "未知號碼" })}（${escapeHtml(record.callDirection.label)}）</td>
        </tr>
        <tr>
          <td style="padding:8px 12px;background:#F8FAFC;border:1px solid #E2E8F0;font-weight:600;">通話時間／時長</td>
          <td style="padding:8px 12px;border:1px solid #E2E8F0;">${escapeHtml(formatTimestamp(record.startedAtMillis))}（${escapeHtml(formatDuration(record.durationSeconds))}）</td>
        </tr>${if (record.promptTemplateName.isNotBlank()) """
        <tr>
          <td style="padding:8px 12px;background:#F8FAFC;border:1px solid #E2E8F0;font-weight:600;">使用分析範本</td>
          <td style="padding:8px 12px;border:1px solid #E2E8F0;">${escapeHtml(record.promptTemplateName)}</td>
        </tr>""" else ""}
      </table>

      <h3 style="font-size:16px;color:#0F766E;border-bottom:2px solid #CCFBF1;padding-bottom:6px;margin:20px 0 10px 0;">通話討論摘要</h3>
      <div style="background:#F8FAFC;border-left:4px solid #0F766E;padding:12px 16px;line-height:1.7;font-size:14px;">
        $summaryHtml
      </div>

      <h3 style="font-size:16px;color:#0F766E;border-bottom:2px solid #CCFBF1;padding-bottom:6px;margin:20px 0 10px 0;">後續待辦／追蹤事項</h3>
      <div style="padding:8px 12px;line-height:1.6;font-size:14px;">
        $actionItemsHtml
      </div>

      <h3 style="font-size:16px;color:#0F766E;border-bottom:2px solid #CCFBF1;padding-bottom:6px;margin:20px 0 10px 0;">完整通話逐字稿</h3>
      <div style="background:#F8FAFC;border:1px solid #E2E8F0;border-radius:8px;padding:14px 16px;line-height:1.75;font-size:13px;color:#334155;">
        $transcriptHtml
      </div>

      <p style="margin-top:20px;font-size:12px;color:#64748B;border-top:1px solid #E2E8F0;padding-top:12px;">
        ※ 注意：本信件由 AI 語音辨識自動生成，個管師於歸檔病歷或臨床處置前，請務必再次核對病人姓名、病歷號碼／身分證字號及醫囑細節。
      </p>
    </div>
  </div>
</body>
</html>
        """.trimIndent()
    }

    fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.TAIWAN)
        return sdf.format(Date(millis))
    }

    fun formatShortTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN)
        return sdf.format(Date(millis))
    }

    fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val mins = safe / 60
        val secs = safe % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    internal fun escapeHtml(input: String): String =
        input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
