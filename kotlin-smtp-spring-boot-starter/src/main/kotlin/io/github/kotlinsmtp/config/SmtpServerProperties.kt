package io.github.kotlinsmtp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties(prefix = "smtp")
class SmtpServerProperties {
    var port: Int = 25
    var hostname: String = "localhost"
    var serviceName: String = "kotlin-smtp"
    var ssl: SslConfig = SslConfig()
    var storage: StorageConfig = StorageConfig()
    var routing: RoutingConfig = RoutingConfig()
    var relay: RelayConfig = RelayConfig()
    var spool: SpoolConfig = SpoolConfig()
    var auth: AuthConfig = AuthConfig()
    var rateLimit: RateLimitConfig = RateLimitConfig()
    var features: FeaturesConfig = FeaturesConfig()
    var proxy: ProxyConfig = ProxyConfig()
    var listeners: List<ListenerConfig> = emptyList()

    data class StorageConfig(
        var mailboxDir: String = "",
        var tempDir: String = "",
        var listsDir: String = "", // EXPN용 로컬 리스트(기능 우선)
    ) {
        val mailboxPath: Path get() = Path.of(mailboxDir)
        val tempPath: Path get() = Path.of(tempDir)
        val listsPath: Path get() = Path.of(listsDir)

        fun validate() {
            require(mailboxDir.isNotBlank()) {
                "smtp.storage.mailboxDir must be configured (e.g., ./data/mailboxes or /var/smtp/mailboxes)"
            }
            require(tempDir.isNotBlank()) {
                "smtp.storage.tempDir must be configured (e.g., ./data/temp or /var/smtp/temp)"
            }
            require(listsDir.isNotBlank()) {
                "smtp.storage.listsDir must be configured (e.g., ./data/lists or /var/smtp/lists)"
            }
        }
    }

    /**
     * 로컬 도메인 판정(로컬 전달 vs 외부 릴레이 분기)에 사용합니다.
     *
     * @property localDomain 로컬 전달로 판정할 기본 도메인
     */
    data class RoutingConfig(
        var localDomain: String = "",
    )

    data class RelayConfig(
        var enabled: Boolean = false,
        /**
         * 레거시 키: `smtp.relay.localDomain`
         *
         * - 신규 구성에서는 `smtp.routing.localDomain` 사용을 권장합니다.
         * - 호환을 위해 유지하며, `smtp.routing.localDomain`이 비어있을 때 fallback으로만 사용합니다.
         */
        @Deprecated("Use smtp.routing.localDomain instead")
        var localDomain: String = "",
    )

    @Suppress("DEPRECATION")
    fun effectiveLocalDomain(): String {
        val r = routing.localDomain.trim()
        if (r.isNotEmpty()) return r
        return relay.localDomain.trim()
    }

    data class SpoolConfig(
        var dir: String = "",
        var maxRetries: Int = 5,
        var retryDelaySeconds: Long = 60,
    ) {
        val path: Path get() = Path.of(dir)

        fun validate() {
            require(dir.isNotBlank()) {
                "smtp.spool.dir must be configured (e.g., ./data/spool or /var/smtp/spool)"
            }
            require(maxRetries >= 0) {
                "smtp.spool.maxRetries must be >= 0"
            }
            require(retryDelaySeconds > 0) {
                "smtp.spool.retryDelaySeconds must be > 0"
            }
        }
    }

    data class AuthConfig(
        var enabled: Boolean = false,
        var required: Boolean = false,
        var users: Map<String, String> = emptyMap(),
        // 공유 AUTH Rate Limiter 설정
        var rateLimitEnabled: Boolean = true,
        var rateLimitMaxFailures: Int = 5, // 5분 내 최대 실패 횟수
        var rateLimitWindowSeconds: Long = 300, // 5분
        var rateLimitLockoutSeconds: Long = 600, // 10분
    )

    data class RateLimitConfig(
        var maxConnectionsPerIp: Int = 10,
        var maxMessagesPerIpPerHour: Int = 100,
    )

    /**
     * PROXY protocol(v1) 지원 시 신뢰 프록시 대역 설정
     *
     * - 보안상 필수: PROXY 헤더는 스푸핑이 가능하므로, LB/HAProxy 등 "신뢰 가능한 프록시"에서만 수용해야 합니다.
     * - 기본값은 로컬(loopback)만 신뢰합니다. 운영에서는 프록시의 소스 IP/CIDR을 반드시 추가하세요.
     *
     * @property trustedCidrs 신뢰할 프록시 IP/CIDR 목록
     */
    data class ProxyConfig(
        var trustedCidrs: List<String> = listOf("127.0.0.1/32", "::1/128"),
    )

    /**
     * 인터넷 노출 기본값은 보수적으로 off.
     * 필요한 경우에만 기능을 켜고(특히 VRFY/ETRN), 접근제어(관리망/인증)와 함께 운영하세요.
     *
     * @property vrfyEnabled VRFY 명령 활성화 여부
     * @property etrnEnabled ETRN 명령 활성화 여부
     * @property expnEnabled EXPN 명령 활성화 여부
     */
    data class FeaturesConfig(
        var vrfyEnabled: Boolean = false,
        var etrnEnabled: Boolean = false,
        var expnEnabled: Boolean = false,
    )

    /**
     * 리스너(포트)별 정책 분리
     *
     * - MTA(25): 보통 AUTH 미사용(또는 선택), STARTTLS는 opportunistic
     * - Submission(587): 보통 STARTTLS + AUTH 강제
     * - SMTPS(465): implicit TLS + AUTH 강제
     *
     * @property port 리스너 포트(0이면 OS가 가용 포트 자동 할당)
     * @property serviceName 리스너별 서비스명(미지정 시 smtp.serviceName 사용)
     * @property implicitTls 접속 즉시 TLS를 시작할지 여부
     * @property enableStartTls STARTTLS 지원 여부
     * @property enableAuth AUTH 지원 여부
     * @property requireAuthForMail MAIL FROM 이전 인증 강제 여부
     * @property proxyProtocol 리스너 단위 PROXY protocol(v1) 수용 여부
     */
    data class ListenerConfig(
        var port: Int = 25,
        var serviceName: String? = null,
        var implicitTls: Boolean = false,
        var enableStartTls: Boolean = true,
        var enableAuth: Boolean = true,
        var requireAuthForMail: Boolean = false,
        var proxyProtocol: Boolean = false, // HAProxy PROXY v1 사용 여부(해당 리스너 전용)
    ) {
        fun validate() {
            // port 0은 시스템이 임의의 사용 가능한 포트를 할당하도록 함 (테스트용)
            require(port in 0..65535) {
                "Listener port must be between 0 and 65535, got: $port"
            }
        }
    }

    /**
     * 전체 설정을 검증합니다.
     * - KotlinSmtpAutoConfiguration.smtpServers()에서 호출됩니다.
     */
    fun validate() {
        // 저장소/스풀 필수 경로 검증
        storage.validate()
        spool.validate()

        // 리스너 설정 검증
        listeners.forEach { listener ->
            listener.validate()
        }

        // 단일 포트 모드일 경우에도 포트 범위 검증
        // port 0은 시스템이 임의의 사용 가능한 포트를 할당하도록 함 (테스트용)
        if (listeners.isEmpty()) {
            require(port in 0..65535) {
                "smtp.port must be between 0 and 65535, got: $port"
            }
        }

        // 로컬 도메인 검증
        val localDomain = effectiveLocalDomain().trim()
        require(localDomain.isNotBlank()) {
            "smtp.routing.localDomain must be configured (e.g., mydomain.com)"
        }

        // SSL/TLS 설정 검증
        ssl.validate()

        // Rate limit 설정 검증
        require(rateLimit.maxConnectionsPerIp > 0) {
            "smtp.rateLimit.maxConnectionsPerIp must be > 0"
        }
        require(rateLimit.maxMessagesPerIpPerHour > 0) {
            "smtp.rateLimit.maxMessagesPerIpPerHour must be > 0"
        }

        // AUTH rate limit 설정 검증
        if (auth.rateLimitEnabled) {
            require(auth.rateLimitMaxFailures > 0) {
                "smtp.auth.rateLimitMaxFailures must be > 0"
            }
            require(auth.rateLimitWindowSeconds > 0) {
                "smtp.auth.rateLimitWindowSeconds must be > 0"
            }
            require(auth.rateLimitLockoutSeconds > 0) {
                "smtp.auth.rateLimitLockoutSeconds must be > 0"
            }
        }
    }
}
