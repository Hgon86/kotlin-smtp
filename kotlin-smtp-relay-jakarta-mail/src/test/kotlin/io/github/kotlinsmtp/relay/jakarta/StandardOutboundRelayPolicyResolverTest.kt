package io.github.kotlinsmtp.relay.jakarta

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class StandardOutboundRelayPolicyResolverTest {
    @Test
    fun `mta sts enforce requires tls and certificate validation`() {
        val resolver = StandardOutboundRelayPolicyResolver(
            mtaStsEnabled = true,
            daneEnabled = false,
            dnsLookup = FakeDnsLookup(
                txt = mapOf("_mta-sts.example.com" to listOf("v=STSv1; id=abc")),
                tlsa = emptySet(),
            ),
            mtaStsFetcher = MtaStsPolicyFetcher {
                """
                version: STSv1
                mode: enforce
                mx: *.example.com
                max_age: 86400
                """.trimIndent()
            },
        )

        val policy = resolver.resolve("example.com")

        assertNotNull(policy)
        assertTrue(policy!!.requireTls)
        assertTrue(policy.requireValidCertificate)
    }

    @Test
    fun `dane tlsa signal requires tls and certificate validation`() {
        val resolver = StandardOutboundRelayPolicyResolver(
            mtaStsEnabled = false,
            daneEnabled = true,
            dnsLookup = FakeDnsLookup(
                txt = emptyMap(),
                tlsa = setOf("_25._tcp.example.com"),
            ),
            mtaStsFetcher = MtaStsPolicyFetcher { null },
        )

        val policy = resolver.resolve("example.com")

        assertNotNull(policy)
        assertTrue(policy!!.requireTls)
        assertTrue(policy.requireValidCertificate)
    }

    @Test
    fun `mta sts testing mode does not enforce tls`() {
        val resolver = StandardOutboundRelayPolicyResolver(
            mtaStsEnabled = true,
            daneEnabled = false,
            dnsLookup = FakeDnsLookup(
                txt = mapOf("_mta-sts.example.com" to listOf("v=STSv1; id=abc")),
                tlsa = emptySet(),
            ),
            mtaStsFetcher = MtaStsPolicyFetcher {
                """
                version: STSv1
                mode: testing
                max_age: 86400
                """.trimIndent()
            },
        )

        val policy = resolver.resolve("example.com")

        assertNotNull(policy)
        assertFalse(policy!!.requireTls)
        assertFalse(policy.requireValidCertificate)
    }

    @Test
    fun `resolver caches mta sts policy and avoids repeated fetch`() {
        val fetchCount = AtomicInteger(0)
        val resolver = StandardOutboundRelayPolicyResolver(
            mtaStsEnabled = true,
            daneEnabled = false,
            dnsLookup = FakeDnsLookup(
                txt = mapOf("_mta-sts.example.com" to listOf("v=STSv1; id=abc")),
                tlsa = emptySet(),
            ),
            mtaStsFetcher = MtaStsPolicyFetcher {
                fetchCount.incrementAndGet()
                """
                version: STSv1
                mode: enforce
                max_age: 86400
                """.trimIndent()
            },
        )

        resolver.resolve("example.com")
        resolver.resolve("example.com")

        assertTrue(fetchCount.get() == 1)
    }

    @Test
    fun `mta sts mode none resolves to no policy`() {
        val resolver = StandardOutboundRelayPolicyResolver(
            mtaStsEnabled = true,
            daneEnabled = false,
            dnsLookup = FakeDnsLookup(
                txt = mapOf("_mta-sts.example.com" to listOf("v=STSv1; id=abc")),
                tlsa = emptySet(),
            ),
            mtaStsFetcher = MtaStsPolicyFetcher {
                """
                version: STSv1
                mode: none
                max_age: 86400
                """.trimIndent()
            },
        )

        val policy = resolver.resolve("example.com")
        assertNull(policy)
    }

    private class FakeDnsLookup(
        private val txt: Map<String, List<String>>,
        private val tlsa: Set<String>,
    ) : OutboundPolicyDnsLookup {
        override fun lookupTxt(dnsName: String): List<String> = txt[dnsName] ?: emptyList()

        override fun hasTlsa(dnsName: String): Boolean = dnsName in tlsa
    }
}
