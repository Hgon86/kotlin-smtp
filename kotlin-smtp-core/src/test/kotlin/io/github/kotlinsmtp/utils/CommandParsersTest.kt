package io.github.kotlinsmtp.utils

import io.github.kotlinsmtp.exception.SmtpSendResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandParsersTest {
    @Test
    fun parseMailArguments_parsesAddressAndUppercasesParams() {
        val parsed = parseMailArguments("MAIL FROM:<User@example.com> size=1234 smtputf8 BODY=8BITMIME")

        assertEquals("User@example.com", parsed.address)
        assertEquals("1234", parsed.parameters["SIZE"])
        assertEquals("", parsed.parameters["SMTPUTF8"])
        assertEquals("8BITMIME", parsed.parameters["BODY"])
    }

    @Test
    fun parseMailArguments_acceptsEmptyReversePath() {
        val parsed = parseMailArguments("MAIL FROM:<>")

        assertEquals("", parsed.address)
        assertEquals(emptyMap(), parsed.parameters)
    }

    @Test
    fun parseRcptArguments_requiresRcptKeyword() {
        val ex = assertFailsWith<IllegalArgumentException> {
            parseRcptArguments("MAIL TO:<rcpt@example.com>")
        }

        assertEquals("Missing RCPT TO keyword", ex.message)
    }

    @Test
    fun parseRcptArguments_rejectsTooManyParameters() {
        val args = (1..11).joinToString(" ") { "X$it=1" }

        val ex = assertFailsWith<IllegalArgumentException> {
            parseRcptArguments("RCPT TO:<rcpt@example.com> $args")
        }

        assertEquals("Too many parameters (max 10)", ex.message)
    }

    @Test
    fun ensureNoUnsupportedParams_throws555ForBlockedParameter() {
        val ex = assertFailsWith<SmtpSendResponse> {
            mapOf("BODY" to "8BITMIME").ensureNoUnsupportedParams(setOf("BODY"))
        }

        assertEquals(555, ex.statusCode)
        assertEquals("BODY parameter not supported", ex.message)
    }
}
