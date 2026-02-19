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
 * DSN (Delivery Status Notification) generator based on jakarta-mail.
 */
public class JakartaMailDsnSender(
    private val serverHostname: String,
    private val store: DsnStore,
) : DsnSender {
    private val maxAttachBytes = 256 * 1024

    private companion object {
        val ENHANCED_STATUS_RE: Regex = Regex("\\b([245]\\.\\d{1,3}\\.\\d{1,3})\\b")
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
        if (shouldSuppressDsn(originalMessagePath)) return

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
                setHeader("X-Loop", serverHostname)
                setContent(multipart)
                setHeader("Content-Type", ct.toString())
            }
            Files.newOutputStream(tempFile).use { out -> mime.writeTo(out) }

            store.enqueue(
                rawMessagePath = tempFile,
                // Keep DSN (bounce) envelope sender as empty reverse-path (<>).
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
     * Map failure reason string into RFC 3464 core fields (Status/Diagnostic-Code).
     *
     * @param reason Failure reason from relay/delivery layer
     * @return Status code and diagnostic-code representation aligned with RFC 3464
     */
    private fun mapFailureToDsnFields(reason: String?): DsnStatusFields {
        val diagnosticText = reason?.ifBlank { null } ?: "Delivery failed"
        val enhancedFromText = extractEnhancedStatus(reason)
        val smtpCode = smtpReplyCode(reason)
        val status = when {
            enhancedFromText?.startsWith("5.") == true -> enhancedFromText
            enhancedFromText != null -> "5.0.0"
            smtpCode != null -> mapSmtpCodeToEnhancedStatus(smtpCode)
            reasonContainsAny(reason, "null mx", "does not accept email") -> "5.1.10"
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
     * Extract RFC 3464 enhanced status code from failure reason.
     *
     * @param reason Failure reason string
     * @return Extracted enhanced status code, or null if absent
     */
    private fun extractEnhancedStatus(reason: String?): String? {
        if (reason.isNullOrBlank()) return null
        val m = ENHANCED_STATUS_RE.find(reason) ?: return null
        return m.groupValues.getOrNull(1)
    }

    /**
     * Extract SMTP response code from failure reason.
     *
     * @param reason Failure reason string
     * @return 3-digit SMTP response code, or null if absent
     */
    private fun smtpReplyCode(reason: String?): Int? {
        if (reason.isNullOrBlank()) return null
        val m = SMTP_REPLY_CODE_RE.find(reason) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Map SMTP response code to RFC 3464 enhanced status code with simple mapping.
     *
     * @param code SMTP response code
     * @return Mapped RFC 3464 enhanced status code
     */
    private fun mapSmtpCodeToEnhancedStatus(code: Int): String = when (code) {
        421 -> "4.4.2"
        450 -> "4.2.0"
        451 -> "4.3.0"
        452 -> "4.2.2"
        521 -> "5.3.2"
        550 -> "5.1.1"
        551 -> "5.1.6"
        552 -> "5.2.2"
        553 -> "5.1.3"
        554 -> "5.0.0"
        555 -> "5.5.4"
        in 400..499 -> "4.0.0"
        in 500..599 -> "5.0.0"
        else -> "5.0.0"
    }

    /**
     * Check whether the given reason string contains any of the keywords.
     *
     * @param reason Failure reason string
     * @param keywords Keyword list to check
     * @return Whether contained
     */
    private fun reasonContainsAny(reason: String?, vararg keywords: String): Boolean {
        if (reason.isNullOrBlank()) return false
        return keywords.any { keyword -> reason.contains(keyword, ignoreCase = true) }
    }

    private fun sanitizeHeaderValue(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim().take(500)

    /**
     * Suppresses DSN generation for loop-prone auto-generated messages.
     *
     * @param originalMessagePath original message path
     * @return true when DSN must be suppressed
     */
    private fun shouldSuppressDsn(originalMessagePath: Path?): Boolean {
        val path = originalMessagePath ?: return false
        val message = runCatching {
            val props = Properties().apply {
                this["mail.mime.allowutf8"] = "true"
            }
            Files.newInputStream(path).use { input -> MimeMessage(Session.getInstance(props), input) }
        }.getOrNull() ?: return false

        val autoSubmittedValues = message.getHeader("Auto-Submitted")?.toList().orEmpty()
        if (autoSubmittedValues.any { it.trim().lowercase() != "no" }) return true

        val xLoopValues = message.getHeader("X-Loop")?.toList().orEmpty()
        if (xLoopValues.any { it.trim().equals(serverHostname, ignoreCase = true) }) return true

        val precedenceValues = message.getHeader("Precedence")?.toList().orEmpty()
        if (precedenceValues.any { it.trim().lowercase() in setOf("bulk", "junk", "list") }) return true

        val contentType = message.contentType?.lowercase().orEmpty()
        if (contentType.contains("message/delivery-status") ||
            (contentType.contains("multipart/report") && contentType.contains("report-type=delivery-status"))
        ) {
            return true
        }

        return false
    }

    /**
     * Internal model holding RFC 3464 per-recipient status representation.
     *
     * @property status enhanced status code
     * @property diagnosticType Diagnostic-Code type (smtp, x-*)
     * @property diagnosticText Diagnostic-Code body
     */
    private data class DsnStatusFields(
        val status: String,
        val diagnosticType: String,
        val diagnosticText: String,
    )
}
