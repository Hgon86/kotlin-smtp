package io.github.kotlinsmtp.relay.jakarta

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Properties

class OutboundMessageHeaderSupplementTest {
    @Test
    fun `adds Date and Message-ID when both are missing`() {
        val message = parse(
            """
            From: sender@example.com
            To: rcpt@example.net
            Subject: hello

            body
            """.trimIndent(),
        )

        assertNull(message.getHeader("Date"))
        assertNull(message.getHeader("Message-ID"))

        val result = OutboundMessageHeaderSupplement.ensureRequiredHeaders(message, "sender@example.com")

        assertTrue(result.dateAdded)
        assertTrue(result.messageIdAdded)
        assertNotNull(message.getHeader("Date"))
        assertTrue(message.getHeader("Message-ID")[0].contains("@example.com>"))
    }

    @Test
    fun `keeps existing Date and Message-ID`() {
        val message = parse(
            """
            Date: Tue, 01 Jan 2030 00:00:00 +0000
            Message-ID: <existing@test.local>
            From: sender@example.com
            To: rcpt@example.net
            Subject: hello

            body
            """.trimIndent(),
        )

        val result = OutboundMessageHeaderSupplement.ensureRequiredHeaders(message, "sender@example.com")

        assertTrue(!result.dateAdded)
        assertTrue(!result.messageIdAdded)
        assertTrue(message.getHeader("Message-ID")[0] == "<existing@test.local>")
    }

    private fun parse(raw: String): MimeMessage {
        val props = Properties().apply { this["mail.mime.allowutf8"] = "true" }
        return MimeMessage(
            Session.getInstance(props),
            ByteArrayInputStream(raw.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)),
        )
    }
}
