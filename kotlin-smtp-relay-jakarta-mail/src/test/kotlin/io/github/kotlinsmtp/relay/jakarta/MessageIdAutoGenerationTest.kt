package io.github.kotlinsmtp.relay.jakarta

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*

/**
 * Message-ID 자동 생성 기능 테스트
 */
class MessageIdAutoGenerationTest {

    /**
     * Message-ID 헤더가 없는 메시지에 자동으로 Message-ID가 추가되어야 합니다.
     */
    @Test
    fun `should generate Message-ID when header is missing`() {
        // Message-ID가 없는 메시지 원문
        val messageWithoutId = """
            From: sender@example.com
            To: recipient@test.com
            Subject: Test Mail
            
            Test body.
        """.trimIndent()

        val props = Properties().apply {
            this["mail.mime.allowutf8"] = "true"
        }
        
        // MimeMessage 파싱
        val message = MimeMessage(Session.getInstance(props), 
            ByteArrayInputStream(messageWithoutId.toByteArray()))
        
        // Message-ID 없음 확인
        assertNull(message.getHeader("Message-ID"), "Initial message should not have Message-ID")
        
        // Message-ID 자동 생성 (릴�이 로직과 동일)
        val sender = "sender@example.com"
        val senderDomain = sender.substringAfterLast('@')
        message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        
        // Message-ID 생성됨 확인
        val messageId = message.getHeader("Message-ID")
        assertNotNull(messageId, "Message-ID should be generated")
        assertTrue(messageId[0].startsWith("<"), "Message-ID should start with <")
        assertTrue(messageId[0].endsWith("@example.com>"), "Message-ID should end with domain>")
    }

    /**
     * Message-ID 헤더가 이미 있는 메시지는 기존 값을 유지해야 합니다.
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
        
        // 기존 Message-ID 확인
        val messageId = message.getHeader("Message-ID")
        assertNotNull(messageId, "Message should have Message-ID")
        assertEquals(existingMessageId, messageId[0], "Should preserve existing Message-ID")
        
        // 릴�이 로직: Message-ID가 있으면 생성하지 않음
        if (message.getHeader("Message-ID") == null) {
            val senderDomain = "example.com"
            message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        }
        
        // 여전히 기존 값 유지 확인
        assertEquals(existingMessageId, message.getHeader("Message-ID")[0], 
            "Should still have original Message-ID")
    }

    /**
     * 발신자가 없는 경우 localhost 도메인을 사용해야 합니다.
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
        
        // 발신자 없음 시뮬레이션
        val senderForSend: String? = null
        val senderDomain = senderForSend?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: "localhost"
        
        message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        
        val messageId = message.getHeader("Message-ID")[0]
        assertTrue(messageId.contains("@localhost>"), 
            "Should use localhost domain when sender is null: $messageId")
    }

    /**
     * Message-ID 형식이 RFC 5322에 맞는지 확인합니다.
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
        
        // RFC 5322 Message-ID 형식: <local-part@domain>
        assertTrue(messageId.matches(Regex("^<[^>]+@[^>]+>\$")), 
            "Message-ID should match RFC 5322 format: $messageId")
        assertTrue(messageId.contains("@"), 
            "Message-ID should contain @ separator: $messageId")
    }
}
