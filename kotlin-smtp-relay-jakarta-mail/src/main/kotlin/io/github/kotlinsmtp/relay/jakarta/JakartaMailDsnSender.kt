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

    private fun extractEnhancedStatus(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val m = Regex("\\b(\\d\\.\\d\\.\\d)\\b").find(reason) ?: return null
        val code = m.groupValues.getOrNull(1) ?: return null
        return if (code.startsWith("5.")) code else null
    }

    private fun sanitizeHeaderValue(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim().take(500)
}
