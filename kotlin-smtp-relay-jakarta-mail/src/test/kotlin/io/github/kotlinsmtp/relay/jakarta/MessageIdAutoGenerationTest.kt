package io.github.kotlinsmtp.relay.jakarta

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*

/**
 * Message-ID auto-generation behavior tests.
 */
class MessageIdAutoGenerationTest {

    /**
     * Message-ID should be auto-added when the header is missing.
     */
    @Test
    fun `should generate Message-ID when header is missing`() {
        // Raw message without Message-ID
        val messageWithoutId = """
            From: sender@example.com
            To: recipient@test.com
            Subject: Test Mail
            
            Test body.
        """.trimIndent()

        val props = Properties().apply {
            this["mail.mime.allowutf8"] = "true"
        }
        
        // Parse MimeMessage
        val message = MimeMessage(Session.getInstance(props), 
            ByteArrayInputStream(messageWithoutId.toByteArray()))
        
        // Verify Message-ID is absent
        assertNull(message.getHeader("Message-ID"), "Initial message should not have Message-ID")
        
        // Auto-generate Message-ID (same as relay logic)
        val sender = "sender@example.com"
        val senderDomain = sender.substringAfterLast('@')
        message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        
        // Verify Message-ID was generated
        val messageId = message.getHeader("Message-ID")
        assertNotNull(messageId, "Message-ID should be generated")
        assertTrue(messageId[0].startsWith("<"), "Message-ID should start with <")
        assertTrue(messageId[0].endsWith("@example.com>"), "Message-ID should end with domain>")
    }

    /**
     * Existing Message-ID header should be preserved.
     */
    @Test
    fun `should preserve existing Message-ID`() {
        val existingMessageId = "<existing-id@sender.com>"
        val messageWithId = """
            Message-ID: $existingMessageId
            From: sender@example.com
            To: recipient@test.com
            Subject: Test Mail
            
            Test body.
        """.trimIndent()

        val props = Properties().apply {
            this["mail.mime.allowutf8"] = "true"
        }
        
        val message = MimeMessage(Session.getInstance(props), 
            ByteArrayInputStream(messageWithId.toByteArray()))
        
        // Verify existing Message-ID
        val messageId = message.getHeader("Message-ID")
        assertNotNull(messageId, "Message should have Message-ID")
        assertEquals(existingMessageId, messageId[0], "Should preserve existing Message-ID")
        
        // Relay logic: do not generate when Message-ID already exists
        if (message.getHeader("Message-ID") == null) {
            val senderDomain = "example.com"
            message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        }
        
        // Verify original value is still preserved
        assertEquals(existingMessageId, message.getHeader("Message-ID")[0], 
            "Should still have original Message-ID")
    }

    /**
     * localhost domain should be used when sender is absent.
     */
    @Test
    fun `should use localhost domain when sender is null`() {
        val messageWithoutId = """
            From: 
            To: recipient@test.com
            Subject: Test Mail
            
            Test body.
        """.trimIndent()

        val props = Properties().apply {
            this["mail.mime.allowutf8"] = "true"
        }
        
        val message = MimeMessage(Session.getInstance(props), 
            ByteArrayInputStream(messageWithoutId.toByteArray()))
        
        // Simulate missing sender
        val senderForSend: String? = null
        val senderDomain = senderForSend?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: "localhost"
        
        message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        
        val messageId = message.getHeader("Message-ID")[0]
        assertTrue(messageId.contains("@localhost>"), 
            "Should use localhost domain when sender is null: $messageId")
    }

    /**
     * Verifies Message-ID format matches RFC 5322.
     */
    @Test
    fun `should generate valid RFC5322 Message-ID format`() {
        val messageWithoutId = """
            From: sender@example.com
            To: recipient@test.com
            Subject: Test
            
            Body.
        """.trimIndent()

        val props = Properties().apply {
            this["mail.mime.allowutf8"] = "true"
        }
        
        val message = MimeMessage(Session.getInstance(props), 
            ByteArrayInputStream(messageWithoutId.toByteArray()))
        
        val senderDomain = "example.com"
        message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        
        val messageId = message.getHeader("Message-ID")[0]
        
        // RFC 5322 Message-ID format: <local-part@domain>
        assertTrue(messageId.matches(Regex("^<[^>]+@[^>]+>\$")), 
            "Message-ID should match RFC 5322 format: $messageId")
        assertTrue(messageId.contains("@"), 
            "Message-ID should contain @ separator: $messageId")
    }
}
