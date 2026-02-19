package io.github.kotlinsmtp.relay.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RelayClientCidrMatcherTest {

    /**
     * Empty CIDR list should allow any client.
     */
    @Test
    fun `empty cidr list allows any peer`() {
        val matcher = RelayClientCidrMatcher(emptyList())
        assertThat(matcher.isAllowed("203.0.113.10:587")).isTrue()
        assertThat(matcher.isAllowed("[2001:db8::10]:587")).isTrue()
    }

    /**
     * IPv4 CIDR matching should work as expected.
     */
    @Test
    fun `ipv4 cidr allows matching client`() {
        val matcher = RelayClientCidrMatcher(listOf("10.10.0.0/16"))
        assertThat(matcher.isAllowed("10.10.2.3:2525")).isTrue()
        assertThat(matcher.isAllowed("10.11.2.3:2525")).isFalse()
    }

    /**
     * IPv6 CIDR matching should work as expected.
     */
    @Test
    fun `ipv6 cidr allows matching client`() {
        val matcher = RelayClientCidrMatcher(listOf("2001:db8::/32"))
        assertThat(matcher.isAllowed("[2001:db8::1]:2525")).isTrue()
        assertThat(matcher.isAllowed("[2001:4860::1]:2525")).isFalse()
    }

    /**
     * Hostname-style client address should be rejected without DNS resolution.
     */
    @Test
    fun `hostname peer address is rejected`() {
        val matcher = RelayClientCidrMatcher(listOf("10.0.0.0/8"))
        assertThat(matcher.isAllowed("mail.internal.local:587")).isFalse()
    }

    /**
     * CIDR host must be an IP literal.
     */
    @Test
    fun `cidr host must be ip literal`() {
        assertThatThrownBy {
            RelayClientCidrMatcher(listOf("mail.internal.local/24"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
