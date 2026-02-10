package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.DsnStore
import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import java.util.UUID

/**
 * jakarta-mail 기반 DSN(Delivery Status Notification) 생성기.
 */
public class JakartaMailDsnSender(
    private val serverHostname: String,
    private val store: DsnStore,
) : DsnSender {
    private val maxAttachBytes = 256 * 1024

    private companion object {
        val ENHANCED_STATUS_RE: Regex = Regex("\\b([245]\\.\\d\\.\\d)\\b")
        val SMTP_REPLY_CODE_RE: Regex = Regex("\\b([245]\\d{2})\\b")
    }

    override fun sendPermanentFailure(
        sender: String?,
        failedRecipients: List<Pair<String, String>>,
        originalMessageId: String,
        originalMessagePath: Path?,
        dsnEnvid: String?,
        dsnRet: String?,
        rcptDsn: Map<String, RcptDsn>,
    ) {
        val envelopeRecipient = sender?.takeIf { it.isNotBlank() && it != "<>" } ?: return
        if (failedRecipients.isEmpty()) return

        val fromHeader = "MAILER-DAEMON@$serverHostname"
        val subject = "Delivery Status Notification (Failure)"

        val multipart = MimeMultipart("report")
        val ct = ContentType(multipart.contentType).apply { setParameter("report-type", "delivery-status") }

        multipart.addBodyPart(MimeBodyPart().apply {
            setText(
                buildString {
                    appendLine("We're sorry. Your email with internal id $originalMessageId could not be delivered to one or more recipients.")
                    appendLine()
                    failedRecipients.forEach { (rcpt, reason) ->
                        appendLine("- $rcpt :: ${reason.ifBlank { "Delivery failed" }}")
                    }
                    appendLine()
                    appendLine("If this keeps happening, please contact the server administrator at postmaster@$serverHostname.")
                },
                Charsets.UTF_8.name()
            )
        })

        val deliveryStatusText = buildDeliveryStatus(
            failedRecipients = failedRecipients,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn,
        )
        multipart.addBodyPart(MimeBodyPart().apply {
            dataHandler = DataHandler(
                ByteArrayDataSource(
                    deliveryStatusText.toByteArray(Charsets.UTF_8),
                    "message/delivery-status",
                )
            )
            setHeader("Content-Type", "message/delivery-status")
        })

        val originalPart = buildOriginalMessagePart(originalMessagePath, dsnRet)
        if (originalPart != null) multipart.addBodyPart(originalPart)

        val tempFile = Files.createTempFile("dsn_", ".eml")
        try {
            val mailSession = Session.getInstance(Properties())
            val mime = MimeMessage(mailSession).apply {
                setFrom(InternetAddress(fromHeader))
                setRecipient(MimeMessage.RecipientType.TO, InternetAddress(envelopeRecipient))
                setSubject(subject, Charsets.UTF_8.name())
                sentDate = Date()
                setHeader("Message-ID", "<dsn-${UUID.randomUUID()}@$serverHostname>")
                setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
                setHeader("Auto-Submitted", "auto-replied")
                setContent(multipart)
                setHeader("Content-Type", ct.toString())
            }
            Files.newOutputStream(tempFile).use { out -> mime.writeTo(out) }

            store.enqueue(
                rawMessagePath = tempFile,
                // DSN(바운스)의 envelope sender는 빈 reverse-path(<>)로 둡니다.
                envelopeSender = "",
                recipients = listOf(envelopeRecipient),
                messageId = originalMessageId,
                authenticated = false,
                dsnRet = null,
                dsnEnvid = null,
                rcptDsn = emptyMap(),
            )
        } finally {
            runCatching { Files.deleteIfExists(tempFile) }
        }
    }

    private fun buildDeliveryStatus(
        failedRecipients: List<Pair<String, String>>,
        dsnEnvid: String?,
        rcptDsn: Map<String, RcptDsn>,
    ): String = buildString {
        appendLine("Reporting-MTA: dns; $serverHostname")
        if (!dsnEnvid.isNullOrBlank()) {
            appendLine("Original-Envelope-Id: ${sanitizeHeaderValue(dsnEnvid)}")
        }
        appendLine("Arrival-Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())}")
        appendLine()

        failedRecipients.forEachIndexed { idx, (rcpt, reason) ->
            val statusField = mapFailureToDsnFields(reason)
            appendLine("Final-Recipient: rfc822; ${sanitizeHeaderValue(rcpt)}")
            val orcpt = rcptDsn[rcpt]?.orcpt
            if (!orcpt.isNullOrBlank()) {
                appendLine("Original-Recipient: ${sanitizeHeaderValue(orcpt)}")
            }
            appendLine("Action: failed")
            appendLine("Status: ${statusField.status}")
            appendLine("Diagnostic-Code: ${statusField.diagnosticType}; ${sanitizeHeaderValue(statusField.diagnosticText)}")
            if (idx != failedRecipients.lastIndex) appendLine()
        }
    }

    private fun buildOriginalMessagePart(originalMessagePath: Path?, dsnRet: String?): MimeBodyPart? {
        val path = originalMessagePath ?: return null
        if (!runCatching { Files.exists(path) }.getOrDefault(false)) return null

        val normalizedRet = dsnRet?.uppercase()
        val size = runCatching { Files.size(path) }.getOrNull()

        if (normalizedRet == "FULL" && size != null && size <= maxAttachBytes) {
            val raw = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
            return MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(raw, "message/rfc822"))
                setHeader("Content-Type", "message/rfc822")
            }
        }

        val headersText = runCatching {
            val props = Properties().apply {
                this["mail.mime.allowutf8"] = "true"
            }
            val msg = Files.newInputStream(path).use { input -> MimeMessage(Session.getInstance(props), input) }
            val e = msg.allHeaderLines
            val lines = buildList {
                while (e.hasMoreElements()) {
                    add(e.nextElement().toString())
                }
            }.joinToString("\r\n")
            "$lines\r\n\r\n"
        }.getOrNull() ?: return null

        return MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(headersText.toByteArray(Charsets.UTF_8), "text/rfc822-headers"))
            setHeader("Content-Type", "text/rfc822-headers")
        }
    }

    /**
     * 실패 사유 문자열을 RFC 3464 핵심 필드(Status/Diagnostic-Code)로 매핑합니다.
     *
     * @param reason 릴레이/전달 계층에서 올라온 실패 사유
     * @return RFC 3464에 맞춘 상태 코드와 진단 코드 표현
     */
    private fun mapFailureToDsnFields(reason: String?): DsnStatusFields {
        val diagnosticText = reason?.ifBlank { null } ?: "Delivery failed"
        val enhancedFromText = extractEnhancedStatus(reason)
        val smtpCode = smtpReplyCode(reason)
        val status = when {
            enhancedFromText?.startsWith("5.") == true -> enhancedFromText
            enhancedFromText != null -> "5.0.0"
            smtpCode != null -> mapSmtpCodeToEnhancedStatus(smtpCode)
            reasonContainsAny(reason, "user unknown", "no such user", "unknown user") -> "5.1.1"
            reasonContainsAny(reason, "unknown host", "domain not found", "no mx records") -> "5.1.2"
            reasonContainsAny(reason, "mailbox full", "quota exceeded", "over quota") -> "5.2.2"
            else -> "5.0.0"
        }
        val diagnosticType = if (smtpCode != null) "smtp" else "x-kotlin-smtp"
        return DsnStatusFields(
            status = status,
            diagnosticType = diagnosticType,
            diagnosticText = diagnosticText,
        )
    }

    /**
     * 실패 사유에서 RFC 3464 enhanced status code를 추출합니다.
     *
     * @param reason 실패 사유 문자열
     * @return 추출된 enhanced status code, 없으면 null
     */
    private fun extractEnhancedStatus(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val m = ENHANCED_STATUS_RE.find(reason) ?: return null
        return m.groupValues.getOrNull(1)
    }

    /**
     * 실패 사유에서 SMTP 응답 코드를 추출합니다.
     *
     * @param reason 실패 사유 문자열
     * @return SMTP 3자리 응답 코드, 없으면 null
     */
    private fun smtpReplyCode(reason: String?): Int? {
        if (reason.isNullOrBlank()) return null
        val m = SMTP_REPLY_CODE_RE.find(reason) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
    }

    /**
     * SMTP 응답 코드를 RFC 3464 enhanced status code로 단순 매핑합니다.
     *
     * @param code SMTP 응답 코드
     * @return 매핑된 RFC 3464 enhanced status code
     */
    private fun mapSmtpCodeToEnhancedStatus(code: Int): String = when (code) {
        550, 551, 553 -> "5.1.1"
        552 -> "5.2.2"
        554 -> "5.0.0"
        in 500..599 -> "5.0.0"
        else -> "5.0.0"
    }

    /**
     * 주어진 사유 문자열이 키워드 중 하나를 포함하는지 확인합니다.
     *
     * @param reason 실패 사유 문자열
     * @param keywords 점검할 키워드 목록
     * @return 포함 여부
     */
    private fun reasonContainsAny(reason: String?, vararg keywords: String): Boolean {
        if (reason.isNullOrBlank()) return false
        return keywords.any { keyword -> reason.contains(keyword, ignoreCase = true) }
    }

    private fun sanitizeHeaderValue(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim().take(500)

    /**
     * RFC 3464의 per-recipient 상태 표현을 담는 내부 모델입니다.
     *
     * @property status enhanced status code
     * @property diagnosticType Diagnostic-Code 타입(smtp, x-*)
     * @property diagnosticText Diagnostic-Code 본문
     */
    private data class DsnStatusFields(
        val status: String,
        val diagnosticType: String,
        val diagnosticText: String,
    )
}
