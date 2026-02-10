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

    /**
     * 모든 도메인에 적용할 기본 Smart Host 경로.
     *
     * null이면 기본 동작은 MX 직접 전송입니다.
     */
    var defaultRoute: SmartHostRouteProperties? = null

    /**
     * 수신자 도메인별 Smart Host 라우팅 목록.
     *
     * domain에는 `example.com` 또는 와일드카드 `*`를 사용할 수 있습니다.
     */
    var routes: List<DomainRouteProperties> = emptyList()

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

    /**
     * Smart Host 경로 설정.
     *
     * @property host 대상 SMTP 서버 호스트
     * @property port 대상 SMTP 서버 포트
     * @property username SMTP AUTH 사용자명(선택)
     * @property password SMTP AUTH 비밀번호(선택)
     * @property startTlsEnabled STARTTLS 시도 여부(선택)
     * @property startTlsRequired STARTTLS 필수 여부(선택)
     * @property checkServerIdentity 서버 인증서 호스트명 검증 여부(선택)
     * @property trustAll 개발/테스트용 trust-all 여부(선택)
     * @property trustHosts 신뢰할 서버 호스트 목록(선택)
     */
    open class SmartHostRouteProperties {
        var host: String = ""
        var port: Int = 25
        var username: String = ""
        var password: String = ""
        var startTlsEnabled: Boolean? = null
        var startTlsRequired: Boolean? = null
        var checkServerIdentity: Boolean? = null
        var trustAll: Boolean? = null
        var trustHosts: List<String>? = null
    }

    /**
     * 도메인 매칭 Smart Host 경로 설정.
     *
     * @property domain 매칭할 수신자 도메인(예: `govkorea.kr`, `*`)
     */
    class DomainRouteProperties : SmartHostRouteProperties() {
        var domain: String = ""
    }
}
