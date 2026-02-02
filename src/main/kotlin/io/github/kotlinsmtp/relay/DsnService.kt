package io.github.kotlinsmtp.relay

import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.spool.MailSpooler
import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.ContentType
import jakarta.mail.util.ByteArrayDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.Properties
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class DsnService(
    private val serverHostname: String,
) {
    var spooler: MailSpooler? = null

    private val maxAttachBytes = 256 * 1024 // DSN에 원문을 붙일 때의 상한(운영 안전)

    fun sendPermanentFailure(
        sender: String?,
        failedRecipients: List<Pair<String, String>>,
        originalMessageId: String,
        originalMessagePath: Path? = null,
        dsnEnvid: String? = null,
        dsnRet: String? = null,
        rcptDsn: Map<String, RcptDsn> = emptyMap(),
    ) {
        val envelopeRecipient = sender?.takeIf { it.isNotBlank() && it != "<>" } ?: return
        if (failedRecipients.isEmpty()) return

        val fromHeader = "MAILER-DAEMON@$serverHostname"
        val subject = "Delivery Status Notification (Failure)"

        // RFC 3464: multipart/report; report-type=delivery-status
        val multipart = MimeMultipart("report")
        val ct = ContentType(multipart.contentType).apply { setParameter("report-type", "delivery-status") }

        // 1) human-readable
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

        // 2) message/delivery-status
        val deliveryStatusText = buildDeliveryStatus(
            failedRecipients = failedRecipients,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn,
        )
        multipart.addBodyPart(MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(deliveryStatusText.toByteArray(Charsets.UTF_8), "message/delivery-status"))
            setHeader("Content-Type", "message/delivery-status")
        })

        // 3) 원문(헤더/전체) 첨부: RET 정책에 따라 보수적으로 포함
        val originalPart = buildOriginalMessagePart(originalMessagePath, dsnRet)
        if (originalPart != null) {
            multipart.addBodyPart(originalPart)
        }

        val tempFile = Files.createTempFile("dsn_", ".eml")
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
        spooler?.enqueue(
            rawMessagePath = tempFile,
            // DSN(바운스)의 엔벌로프 sender는 빈 reverse-path(<>)가 되어야 루프/백스캐터 위험이 줄어듭니다.
            // (From 헤더는 MAILER-DAEMON 으로 유지)
            sender = "",
            recipients = listOf(envelopeRecipient),
            messageId = originalMessageId,
            authenticated = false
        )
        Files.deleteIfExists(tempFile)
    }

    private fun buildDeliveryStatus(
        failedRecipients: List<Pair<String, String>>,
        dsnEnvid: String?,
        rcptDsn: Map<String, RcptDsn>,
    ): String = buildString {
        // per-message fields
        appendLine("Reporting-MTA: dns; $serverHostname")
        if (!dsnEnvid.isNullOrBlank()) {
            appendLine("Original-Envelope-Id: ${sanitizeHeaderValue(dsnEnvid)}")
        }
        appendLine("Arrival-Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())}")
        appendLine()

        // per-recipient blocks
        failedRecipients.forEachIndexed { idx, (rcpt, reason) ->
            appendLine("Final-Recipient: rfc822; ${sanitizeHeaderValue(rcpt)}")
            val orcpt = rcptDsn[rcpt]?.orcpt
            if (!orcpt.isNullOrBlank()) {
                appendLine("Original-Recipient: ${sanitizeHeaderValue(orcpt)}")
            }
            appendLine("Action: failed")
            appendLine("Status: ${extractEnhancedStatus(reason) ?: "5.0.0"}")
            appendLine("Diagnostic-Code: smtp; ${sanitizeHeaderValue(reason.ifBlank { "Delivery failed" })}")
            if (idx != failedRecipients.lastIndex) appendLine()
        }
    }

    private fun buildOriginalMessagePart(originalMessagePath: Path?, dsnRet: String?): MimeBodyPart? {
        val path = originalMessagePath ?: return null
        if (!runCatching { Files.exists(path) }.getOrDefault(false)) return null

        val normalizedRet = dsnRet?.uppercase()
        val size = runCatching { Files.size(path) }.getOrNull()

        // RET=FULL 이면서 너무 크지 않으면 원문 전체를 첨부(message/rfc822)
        if (normalizedRet == "FULL" && size != null && size <= maxAttachBytes) {
            val raw = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
            return MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(raw, "message/rfc822"))
                setHeader("Content-Type", "message/rfc822")
            }
        }

        // 기본: 헤더만 첨부(text/rfc822-headers)
        val headersText = runCatching {
            val props = Properties().apply {
                // SMTPUTF8/UTF-8 헤더 파싱을 위해 허용(최소 구현)
                // TODO: 구현체 버전별 공식 문서 확인 후 고정
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

    private fun extractEnhancedStatus(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val m = Regex("\\b(\\d\\.\\d\\.\\d)\\b").find(reason) ?: return null
        val code = m.groupValues.getOrNull(1) ?: return null
        return if (code.startsWith("5.")) code else null
    }

    private fun sanitizeHeaderValue(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim().take(500)
}
