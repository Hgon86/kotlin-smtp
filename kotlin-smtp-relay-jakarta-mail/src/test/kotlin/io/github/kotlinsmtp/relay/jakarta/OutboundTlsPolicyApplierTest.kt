package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.relay.api.OutboundRelayPolicy
import io.github.kotlinsmtp.relay.api.RelayRoute
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundTlsPolicyApplierTest {
    @Test
    fun `mx policy enforces tls and certificate checks`() {
        val base = OutboundTlsConfig(
            startTlsEnabled = false,
            startTlsRequired = false,
            checkServerIdentity = false,
        )
        val policy = OutboundRelayPolicy(
            requireTls = true,
            requireValidCertificate = true,
            source = "mta-sts",
        )

        val resolved = OutboundTlsPolicyApplier.forMx(base, policy)

        assertTrue(resolved.startTlsEnabled)
        assertTrue(resolved.startTlsRequired)
        assertTrue(resolved.checkServerIdentity)
    }

    @Test
    fun `smart host route keeps route tls unless stricter policy applies`() {
        val base = OutboundTlsConfig(
            startTlsEnabled = true,
            startTlsRequired = false,
            checkServerIdentity = true,
        )
        val route = RelayRoute.SmartHost(
            host = "smtp.example.net",
            port = 587,
            startTlsEnabled = true,
            startTlsRequired = false,
            checkServerIdentity = false,
        )
        val policy = OutboundRelayPolicy(requireTls = true, requireValidCertificate = true)

        val resolved = OutboundTlsPolicyApplier.forSmartHost(base, route, policy)

        assertTrue(resolved.startTlsEnabled)
        assertTrue(resolved.startTlsRequired)
        assertTrue(resolved.checkServerIdentity)
    }
}
