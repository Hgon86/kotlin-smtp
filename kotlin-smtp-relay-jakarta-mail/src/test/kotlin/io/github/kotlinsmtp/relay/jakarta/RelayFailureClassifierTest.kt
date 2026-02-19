package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.relay.api.RelayPermanentException
import io.github.kotlinsmtp.relay.api.RelayTransientException
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.internet.InternetAddress
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayFailureClassifierTest {
    @Test
    fun `5xx smtp return code is classified as permanent`() {
        val error = SMTPAddressFailedException(
            InternetAddress("user@example.com"),
            "RCPT TO",
            550,
            "550 5.1.1 User unknown",
        )

        val classified = RelayFailureClassifier.classify(error, "fallback")

        assertTrue(classified is RelayPermanentException)
        assertEquals("5.1.1", classified.enhancedStatusCode)
    }

    @Test
    fun `4xx smtp return code is classified as transient`() {
        val error = SMTPSendFailedException(
            "DATA",
            421,
            "421 4.4.2 Connection dropped",
            null,
            emptyArray(),
            emptyArray(),
            emptyArray(),
        )

        val classified = RelayFailureClassifier.classify(error, "fallback")

        assertTrue(classified is RelayTransientException)
        assertEquals("4.4.2", classified.enhancedStatusCode)
    }

    @Test
    fun `enhanced status with two-digit detail is parsed`() {
        val error = IllegalStateException("550 5.1.10 Recipient address has null MX")

        val classified = RelayFailureClassifier.classify(error, "fallback")

        assertTrue(classified is RelayPermanentException)
        assertEquals("5.1.10", classified.enhancedStatusCode)
    }

    @Test
    fun `authentication failure is classified as permanent`() {
        val error = AuthenticationFailedException("Authentication failed")

        val classified = RelayFailureClassifier.classify(error, "fallback")

        assertTrue(classified is RelayPermanentException)
        assertEquals("5.7.8", classified.enhancedStatusCode)
    }
}
