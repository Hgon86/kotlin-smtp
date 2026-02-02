package io.github.kotlinsmtp.config

import io.github.kotlinsmtp.auth.AuthRateLimiter
import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.auth.InMemoryAuthService
import io.github.kotlinsmtp.mail.LocalMailboxManager
import io.github.kotlinsmtp.mail.MailRelay
import io.github.kotlinsmtp.protocol.handler.LocalFileMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SimpleSmtpProtocolHandler
import io.github.kotlinsmtp.protocol.handler.LocalDirectoryUserHandler
import io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SmtpUserHandler
import io.github.kotlinsmtp.relay.DsnService
import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.spool.MailDeliveryService
import io.github.kotlinsmtp.spool.MailSpooler
import io.github.kotlinsmtp.storage.FileMessageStore
import io.github.kotlinsmtp.storage.MessageStore
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

@Configuration
@ConfigurationProperties(prefix = "smtp")
class SmtpServerConfig {
    var port: Int = 25
    var hostname: String = "localhost"
    var serviceName: String = "kotlin-smtp"
    var ssl: SslConfig = SslConfig()
    var storage: StorageConfig = StorageConfig()
    var relay: RelayConfig = RelayConfig()
    var spool: SpoolConfig = SpoolConfig()
    var auth: AuthConfig = AuthConfig()
    var rateLimit: RateLimitConfig = RateLimitConfig()
    var features: FeaturesConfig = FeaturesConfig()
    var proxy: ProxyConfig = ProxyConfig()
    var listeners: List<ListenerConfig> = emptyList()

    data class StorageConfig(
        var mailboxDir: String = "C:\\smtp-server\\mailboxes",
        var tempDir: String = "C:\\smtp-server\\temp-mails",
        var listsDir: String = "C:\\smtp-server\\lists", // EXPN용 로컬 리스트(기능 우선)
    ) {
        val mailboxPath: Path get() = Path.of(mailboxDir)
        val tempPath: Path get() = Path.of(tempDir)
        val listsPath: Path get() = Path.of(listsDir)
    }

    data class RelayConfig(
        var enabled: Boolean = false,
        var localDomain: String = "mydomain.com",
        var allowedSenderDomains: List<String> = emptyList(),
        // 안전 기본값: 릴레이는 인증을 요구합니다(운영에서 open relay 방지)
        var requireAuthForRelay: Boolean = true,
        var outboundTls: OutboundTlsConfig = OutboundTlsConfig(),
    )

    /**
     * 아웃바운드 릴레이(TCP 25 등)에서의 TLS/STARTTLS 정책
     *
     * 운영 기본값은 "검증(verify)"이며,
     * 로컬 개발/테스트 환경에서만 trustAll 등을 사용하도록 설정으로 분리합니다.
     */
    data class OutboundTlsConfig(
        var ports: List<Int> = listOf(25), // 운영 기본: MX 릴레이는 25 (개발환경에서만 587 등을 추가)
        var startTlsEnabled: Boolean = true, // 보통은 opportunistic STARTTLS
        var startTlsRequired: Boolean = false, // true면 상대가 STARTTLS 미지원 시 실패 가능
        var checkServerIdentity: Boolean = true, // 가능하면 true 권장
        var trustAll: Boolean = false, // 개발환경 편의용(운영에서는 false 권장)
        var trustHosts: List<String> = emptyList(), // 특정 호스트만 신뢰(예: ["mx.example.com"])
        var connectTimeoutMs: Int = 15_000,
        var readTimeoutMs: Int = 15_000,
    )

    data class SpoolConfig(
        var dir: String = "C:\\smtp-server\\spool",
        var maxRetries: Int = 5,
        var retryDelaySeconds: Long = 60,
    ) {
        val path: Path get() = Path.of(dir)
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
     */
    data class ProxyConfig(
        var trustedCidrs: List<String> = listOf("127.0.0.1/32", "::1/128"),
    )

    /**
     * 인터넷 노출 기본값은 보수적으로 off.
     * 필요한 경우에만 기능을 켜고(특히 VRFY/ETRN), 접근제어(관리망/인증)와 함께 운영하세요.
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
     */
    data class ListenerConfig(
        var port: Int = 25,
        var serviceName: String? = null,
        var implicitTls: Boolean = false,
        var enableStartTls: Boolean = true,
        var enableAuth: Boolean = true,
        var requireAuthForMail: Boolean = false,
        var proxyProtocol: Boolean = false, // HAProxy PROXY v1 사용 여부(해당 리스너 전용)
    )

    @Bean
    fun authService(): AuthService = InMemoryAuthService(
        enabled = auth.enabled,
        required = auth.required,
        users = auth.users
    )

    @Bean
    fun authRateLimiter(): AuthRateLimiter = AuthRateLimiter(
        maxFailuresPerWindow = auth.rateLimitMaxFailures,
        windowSeconds = auth.rateLimitWindowSeconds,
        lockoutDurationSeconds = auth.rateLimitLockoutSeconds,
    )

    @Bean
    fun localMailboxManager(): LocalMailboxManager = LocalMailboxManager(storage.mailboxPath)

    @Bean
    fun dsnService(): DsnService = DsnService(hostname)

    @Bean
    fun mailRelay(): MailRelay =
        MailRelay(
            dispatcherIO = kotlinx.coroutines.Dispatchers.IO,
            tls = relay.outboundTls,
        )

    @Bean
    fun mailDeliveryService(
        localMailboxManager: LocalMailboxManager,
        mailRelay: MailRelay,
        dsnService: DsnService
    ): MailDeliveryService =
        MailDeliveryService(
            localMailboxManager = localMailboxManager,
            mailRelay = mailRelay,
            localDomain = relay.localDomain,
            allowedSenderDomains = relay.allowedSenderDomains,
            requireAuthForRelay = relay.requireAuthForRelay,
        ).apply { attachDsnService(dsnService) }

    @Bean
    fun mailSpooler(deliveryService: MailDeliveryService, dsnService: DsnService): MailSpooler =
        MailSpooler(
            spoolDir = spool.path,
            maxRetries = spool.maxRetries,
            retryDelaySeconds = spool.retryDelaySeconds,
            deliveryService = deliveryService,
            dsnService = dsnService,
            serverHostname = hostname
        ).also { dsnService.spooler = it }

    @Bean
    fun messageStore(): MessageStore =
        // 기능 우선: 파일 기반 임시 저장
        // TODO(storage): DB/S3 등으로 교체 시 구현체만 바꾸도록 경계를 유지합니다.
        FileMessageStore(tempDir = storage.tempPath)

    @Bean
    fun smtpUserHandler(): SmtpUserHandler =
        // TODO(DB/MSA): 로컬 디스크 기반 검증은 단일 노드/개발 환경에 적합
        //               운영에서는 정책/사용자 저장소를 별도 서비스/DB로 이관 권장
        LocalDirectoryUserHandler(
            mailboxDir = storage.mailboxPath,
            localDomain = relay.localDomain,
        )

    @Bean
    fun smtpMailingListHandler(): SmtpMailingListHandler =
        // TODO(DB/MSA): 운영에서는 메일링 리스트/멤버십을 DB 또는 Directory 서비스로 이관 권장
        LocalFileMailingListHandler(
            listsDir = storage.listsPath,
        )

    @Bean
    fun smtpServers(
        deliveryService: MailDeliveryService,
        spooler: MailSpooler,
        userHandler: SmtpUserHandler,
        mailingListHandler: SmtpMailingListHandler,
        messageStore: MessageStore,
        authService: AuthService,
        authRateLimiter: AuthRateLimiter,
    ): List<SmtpServer> {
        // 안전장치: relay.enabled를 켜는 순간, 설정 실수로 open relay가 되는 것을 막습니다.
        if (relay.enabled && !relay.requireAuthForRelay && relay.allowedSenderDomains.isEmpty()) {
            error("Refusing to start: smtp.relay.enabled=true without smtp.relay.requireAuthForRelay=true or smtp.relay.allowedSenderDomains allowlist")
        }

        val cert = if (ssl.enabled) ssl.getCertChainFile() else null
        val key = if (ssl.enabled) ssl.getPrivateKeyFile() else null

        val handlerCreator = {
            SimpleSmtpProtocolHandler(
                messageStore = messageStore,
                relayEnabled = relay.enabled,
                deliveryService = deliveryService,
                spooler = spooler,
            )
        }

        val effectiveListeners = if (listeners.isEmpty()) {
            // 기존 설정 호환: smtp.port 하나만 사용하는 모드
            listOf(
                ListenerConfig(
                    port = port,
                    serviceName = serviceName,
                    implicitTls = false,
                    enableStartTls = true,
                    enableAuth = auth.enabled,
                    requireAuthForMail = auth.required,
                )
            )
        } else listeners

        return effectiveListeners.map { l ->
            SmtpServer(
                port = l.port,
                hostname = hostname,
                serviceName = l.serviceName ?: serviceName,
                authService = authService,
                transactionHandlerCreator = handlerCreator,
                userHandler = userHandler,
                mailingListHandler = mailingListHandler,
                spooler = spooler,
                authRateLimiter = authRateLimiter,
                enableVrfy = features.vrfyEnabled,
                enableEtrn = features.etrnEnabled,
                enableExpn = features.expnEnabled,
                implicitTls = l.implicitTls,
                enableStartTls = l.enableStartTls,
                enableAuth = l.enableAuth && auth.enabled,
                requireAuthForMail = l.requireAuthForMail,
                proxyProtocolEnabled = l.proxyProtocol,
                trustedProxyCidrs = proxy.trustedCidrs,
                certChainFile = cert,
                privateKeyFile = key,
                minTlsVersion = ssl.minTlsVersion,
                tlsHandshakeTimeoutMs = ssl.handshakeTimeoutMs,
                tlsCipherSuites = ssl.cipherSuites,
                maxConnectionsPerIp = rateLimit.maxConnectionsPerIp,
                maxMessagesPerIpPerHour = rateLimit.maxMessagesPerIpPerHour,
            )
        }
    }
}
