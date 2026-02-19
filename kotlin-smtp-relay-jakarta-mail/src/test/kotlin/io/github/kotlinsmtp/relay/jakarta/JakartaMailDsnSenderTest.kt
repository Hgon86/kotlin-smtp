package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.relay.api.DsnStore
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Properties

class JakartaMailDsnSenderTest {

    /**
     * Failure reasons with SMTP code/enhanced code should map to RFC 3464 fields.
     */
    @Test
    fun `maps smtp reply to status and diagnostic code`() {
        var captured: ByteArray? = null
        val store = DsnStore { rawMessagePath, _, _, _, _, _, _, _ ->
            captured = Files.readAllBytes(rawMessagePath)
        }
        val sender = JakartaMailDsnSender(serverHostname = "smtp.test", store = store)

        sender.sendPermanentFailure(
            sender = "bounce@example.com",
            failedRecipients = listOf("user@example.com" to "550 5.1.1 User unknown"),
            originalMessageId = "msg-1",
            originalMessagePath = null,
            dsnEnvid = "envid-1",
            dsnRet = "HDRS",
            rcptDsn = emptyMap(),
        )

        val dsnText = extractDeliveryStatusText(captured)
        assertTrue(dsnText.contains("Status: 5.1.1"), "Expected RFC 3464 status mapping, got:\n$dsnText")
        assertTrue(dsnText.contains("Diagnostic-Code: smtp; 550 5.1.1 User unknown"), "Expected SMTP diagnostic type, got:\n$dsnText")
    }

    /**
     * Failure reasons without SMTP code should use conservative mapping and include ORCPT.
     */
    @Test
    fun `maps heuristic status and includes original recipient`() {
        var captured: ByteArray? = null
        val store = DsnStore { rawMessagePath, _, _, _, _, _, _, _ ->
            captured = Files.readAllBytes(rawMessagePath)
        }
        val sender = JakartaMailDsnSender(serverHostname = "smtp.test", store = store)

        sender.sendPermanentFailure(
            sender = "bounce@example.com",
            failedRecipients = listOf("user@example.com" to "mailbox full"),
            originalMessageId = "msg-2",
            originalMessagePath = null,
            dsnEnvid = null,
            dsnRet = "HDRS",
            rcptDsn = mapOf("user@example.com" to RcptDsn(orcpt = "rfc822;orig@example.net")),
        )

        val dsnText = extractDeliveryStatusText(captured)
        assertTrue(dsnText.contains("Status: 5.2.2"), "Expected mailbox full mapping to 5.2.2, got:\n$dsnText")
        assertTrue(dsnText.contains("Diagnostic-Code: x-kotlin-smtp; mailbox full"), "Expected custom diagnostic type, got:\n$dsnText")
        assertTrue(dsnText.contains("Original-Recipient: rfc822;orig@example.net"), "Expected ORCPT reflection, got:\n$dsnText")
    }

    /**
     * Extracts message/delivery-status part from generated DSN RFC822.
     *
     * @param rawDsn captured DSN RFC822 raw bytes
     * @return delivery-status part text
     */
    private fun extractDeliveryStatusText(rawDsn: ByteArray?): String {
        val bytes = checkNotNull(rawDsn) { "DSN message was not captured" }
        val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(bytes))
        val multipart = message.content as MimeMultipart
        val statusPart = multipart.getBodyPart(1)
        return statusPart.inputStream.readBytes().toString(Charsets.UTF_8)
    }
}
