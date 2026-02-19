package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.relay.api.RelayPermanentException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpoolFailurePolicyTest {
    private val policy = SpoolFailurePolicy()

    @Test
    fun `multi digit enhanced status is treated as permanent`() {
        val ex = RelayPermanentException("550 5.1.10 Recipient domain has null MX")

        assertTrue(policy.isPermanentFailure(ex))
    }

    @Test
    fun `null mx message in illegal state is treated as permanent`() {
        val ex = IllegalStateException("Null MX: domain does not accept email")

        assertTrue(policy.isPermanentFailure(ex))
    }

    @Test
    fun `temporary enhanced status is not treated as permanent`() {
        val ex = RuntimeException("451 4.4.3 DNS temporary failure")

        assertFalse(policy.isPermanentFailure(ex))
    }
}
