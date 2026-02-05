package io.github.kotlinsmtp.config

import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.auth.InMemoryAuthService
import io.github.kotlinsmtp.mail.LocalMailboxManager
import io.github.kotlinsmtp.mail.MailRelay
import io.github.kotlinsmtp.protocol.handler.LocalDirectoryUserHandler
import io.github.kotlinsmtp.protocol.handler.LocalFileMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SimpleSmtpProtocolHandler
import io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SmtpUserHandler
import io.github.kotlinsmtp.relay.DsnService
import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.server.SmtpServerRunner
import io.github.kotlinsmtp.spi.SmtpEventHook
import io.github.kotlinsmtp.spool.MailDeliveryService
import io.github.kotlinsmtp.spool.MailSpooler
import io.github.kotlinsmtp.storage.FileMessageStore
import io.github.kotlinsmtp.storage.MessageStore
import kotlinx.coroutines.Dispatchers
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(SmtpServerProperties::class)
class KotlinSmtpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun authService(props: SmtpServerProperties): AuthService = InMemoryAuthService(
        enabled = props.auth.enabled,
        required = props.auth.required,
        users = props.auth.users
    )

    @Bean
    @ConditionalOnMissingBean
    fun localMailboxManager(props: SmtpServerProperties): LocalMailboxManager =
        LocalMailboxManager(props.storage.mailboxPath)

    @Bean
    @ConditionalOnMissingBean
    fun dsnService(props: SmtpServerProperties): DsnService = DsnService(props.hostname)

    @Bean
    @ConditionalOnMissingBean
    fun mailRelay(props: SmtpServerProperties): MailRelay =
        MailRelay(
            dispatcherIO = Dispatchers.IO,
            tls = props.relay.outboundTls,
        )

    @Bean
    @ConditionalOnMissingBean
    fun mailDeliveryService(
        props: SmtpServerProperties,
        localMailboxManager: LocalMailboxManager,
        mailRelay: MailRelay,
        dsnService: DsnService,
    ): MailDeliveryService =
        MailDeliveryService(
            localMailboxManager = localMailboxManager,
            mailRelay = mailRelay,
            localDomain = props.relay.localDomain,
            allowedSenderDomains = props.relay.allowedSenderDomains,
            requireAuthForRelay = props.relay.requireAuthForRelay,
        ).apply { attachDsnService(dsnService) }

    @Bean
    @ConditionalOnMissingBean
    fun mailSpooler(props: SmtpServerProperties, deliveryService: MailDeliveryService, dsnService: DsnService): MailSpooler =
        MailSpooler(
            spoolDir = props.spool.path,
            maxRetries = props.spool.maxRetries,
            retryDelaySeconds = props.spool.retryDelaySeconds,
            deliveryService = deliveryService,
            dsnService = dsnService,
            serverHostname = props.hostname
        ).also { dsnService.spooler = it }

    @Bean
    @ConditionalOnMissingBean
    fun messageStore(props: SmtpServerProperties): MessageStore =
        // 기능 우선: 파일 기반 임시 저장
        // TODO(storage): DB/S3 등으로 교체 시 구현체만 바꾸도록 경계를 유지합니다.
        FileMessageStore(tempDir = props.storage.tempPath)

    @Bean
    @ConditionalOnMissingBean
    fun smtpUserHandler(props: SmtpServerProperties): SmtpUserHandler =
        // TODO(DB/MSA): 로컬 디스크 기반 검증은 단일 노드/개발 환경에 적합
        //               운영에서는 정책/사용자 저장소를 별도 서비스/DB로 이관 권장
        LocalDirectoryUserHandler(
            mailboxDir = props.storage.mailboxPath,
            localDomain = props.relay.localDomain,
        )

    @Bean
    @ConditionalOnMissingBean
    fun smtpMailingListHandler(props: SmtpServerProperties): SmtpMailingListHandler =
        // TODO(DB/MSA): 운영에서는 메일링 리스트/멤버십을 DB 또는 Directory 서비스로 이관 권장
        LocalFileMailingListHandler(
            listsDir = props.storage.listsPath,
        )

    @Bean
    fun smtpServers(
        props: SmtpServerProperties,
        deliveryService: MailDeliveryService,
        spooler: MailSpooler,
        userHandler: SmtpUserHandler,
        mailingListHandler: SmtpMailingListHandler,
        messageStore: MessageStore,
        authService: AuthService,
        eventHooks: List<SmtpEventHook>,
    ): List<SmtpServer> {
        // Validate required storage paths (no OS-specific defaults)
        props.storage.validate()
        props.spool.validate()

        // 안전장치: relay.enabled를 켜는 순간, 설정 실수로 open relay가 되는 것을 막습니다.
        if (props.relay.enabled && !props.relay.requireAuthForRelay && props.relay.allowedSenderDomains.isEmpty()) {
            error("Refusing to start: smtp.relay.enabled=true without smtp.relay.requireAuthForRelay=true or smtp.relay.allowedSenderDomains allowlist")
        }

        val cert = if (props.ssl.enabled) props.ssl.getCertChainFile() else null
        val key = if (props.ssl.enabled) props.ssl.getPrivateKeyFile() else null

        val handlerCreator = {
            SimpleSmtpProtocolHandler(
                messageStore = messageStore,
                relayEnabled = props.relay.enabled,
                deliveryService = deliveryService,
                spooler = spooler,
            )
        }

        val effectiveListeners = if (props.listeners.isEmpty()) {
            // 기존 설정 호환: smtp.port 하나만 사용하는 모드
            listOf(
                SmtpServerProperties.ListenerConfig(
                    port = props.port,
                    serviceName = props.serviceName,
                    implicitTls = false,
                    enableStartTls = true,
                    enableAuth = props.auth.enabled,
                    requireAuthForMail = props.auth.required,
                )
            )
        } else props.listeners

        return effectiveListeners.map { l ->
            SmtpServer.create(l.port, props.hostname) {
                serviceName = l.serviceName ?: props.serviceName
                useAuthService(authService)
                useProtocolHandlerFactory(handlerCreator)
                useUserHandler(userHandler)
                useMailingListHandler(mailingListHandler)
                useSpooler(spooler)

                // SPI hooks: 코어 이벤트를 외부 인프라(S3/Kafka/DB 등)로 연결합니다.
                eventHooks.forEach { hook -> addEventHook(hook) }

                features.enableVrfy = props.features.vrfyEnabled
                features.enableEtrn = props.features.etrnEnabled
                features.enableExpn = props.features.expnEnabled

                listener.implicitTls = l.implicitTls
                listener.enableStartTls = l.enableStartTls
                listener.enableAuth = l.enableAuth && props.auth.enabled
                listener.requireAuthForMail = l.requireAuthForMail

                proxyProtocol.enabled = l.proxyProtocol
                proxyProtocol.trustedProxyCidrs = props.proxy.trustedCidrs

                tls.certChainPath = cert?.toPath()
                tls.privateKeyPath = key?.toPath()
                tls.minTlsVersion = props.ssl.minTlsVersion
                tls.handshakeTimeoutMs = props.ssl.handshakeTimeoutMs
                tls.cipherSuites = props.ssl.cipherSuites

                rateLimit.maxConnectionsPerIp = props.rateLimit.maxConnectionsPerIp
                rateLimit.maxMessagesPerIpPerHour = props.rateLimit.maxMessagesPerIpPerHour

                authRateLimit.enabled = props.auth.rateLimitEnabled
                authRateLimit.maxFailuresPerWindow = props.auth.rateLimitMaxFailures
                authRateLimit.windowSeconds = props.auth.rateLimitWindowSeconds
                authRateLimit.lockoutDurationSeconds = props.auth.rateLimitLockoutSeconds
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun smtpServerRunner(smtpServers: List<SmtpServer>): SmtpServerRunner =
        SmtpServerRunner(smtpServers)
}
