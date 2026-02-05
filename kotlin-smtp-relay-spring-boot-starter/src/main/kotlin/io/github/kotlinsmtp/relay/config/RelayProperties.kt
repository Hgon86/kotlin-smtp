package io.github.kotlinsmtp.relay.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "smtp.relay")
class RelayProperties {
    var enabled: Boolean = false

    /**
     * 릴레이를 허용할 때, 세션 인증(AUTH)을 요구할지 여부(오픈 릴레이 방지 기본값).
     */
    var requireAuthForRelay: Boolean = true

    /**
     * 인증 없이 릴레이를 허용할 발신 도메인 allowlist.
     *
     * 빈 리스트면 "allowlist 기반 예외"를 사용하지 않습니다.
     */
    var allowedSenderDomains: List<String> = emptyList()

    var outboundTls: OutboundTlsProperties = OutboundTlsProperties()

    class OutboundTlsProperties {
        var ports: List<Int> = listOf(25)
        var startTlsEnabled: Boolean = true
        var startTlsRequired: Boolean = false
        var checkServerIdentity: Boolean = true
        var trustAll: Boolean = false
        var trustHosts: List<String> = emptyList()
        var connectTimeoutMs: Int = 15_000
        var readTimeoutMs: Int = 15_000
    }
}
