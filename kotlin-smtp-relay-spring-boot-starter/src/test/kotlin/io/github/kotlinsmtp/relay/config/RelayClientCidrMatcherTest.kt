package io.github.kotlinsmtp.relay.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RelayClientCidrMatcherTest {

    /**
     * CIDR 목록이 비어있으면 모든 클라이언트를 허용해야 합니다.
     */
    @Test
    fun `empty cidr list allows any peer`() {
        val matcher = RelayClientCidrMatcher(emptyList())
        assertThat(matcher.isAllowed("203.0.113.10:587")).isTrue()
        assertThat(matcher.isAllowed("[2001:db8::10]:587")).isTrue()
    }

    /**
     * IPv4 CIDR 매칭이 정상 동작해야 합니다.
     */
    @Test
    fun `ipv4 cidr allows matching client`() {
        val matcher = RelayClientCidrMatcher(listOf("10.10.0.0/16"))
        assertThat(matcher.isAllowed("10.10.2.3:2525")).isTrue()
        assertThat(matcher.isAllowed("10.11.2.3:2525")).isFalse()
    }

    /**
     * IPv6 CIDR 매칭이 정상 동작해야 합니다.
     */
    @Test
    fun `ipv6 cidr allows matching client`() {
        val matcher = RelayClientCidrMatcher(listOf("2001:db8::/32"))
        assertThat(matcher.isAllowed("[2001:db8::1]:2525")).isTrue()
        assertThat(matcher.isAllowed("[2001:4860::1]:2525")).isFalse()
    }

    /**
     * 호스트명 형태 클라이언트 주소는 DNS 해석 없이 거부해야 합니다.
     */
    @Test
    fun `hostname peer address is rejected`() {
        val matcher = RelayClientCidrMatcher(listOf("10.0.0.0/8"))
        assertThat(matcher.isAllowed("mail.internal.local:587")).isFalse()
    }

    /**
     * CIDR host는 IP 리터럴만 허용해야 합니다.
     */
    @Test
    fun `cidr host must be ip literal`() {
        assertThatThrownBy {
            RelayClientCidrMatcher(listOf("mail.internal.local/24"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
