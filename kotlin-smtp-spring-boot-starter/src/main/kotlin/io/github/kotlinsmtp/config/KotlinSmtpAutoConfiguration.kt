package io.github.kotlinsmtp.config

import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.auth.InMemoryAuthService
import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.mail.LocalMailboxManager
import io.github.kotlinsmtp.metrics.MicrometerSmtpEventHook
import io.github.kotlinsmtp.metrics.MicrometerSpoolMetrics
import io.github.kotlinsmtp.metrics.SpoolMetrics
import io.github.kotlinsmtp.protocol.handler.*
import io.github.kotlinsmtp.relay.api.*
import io.github.kotlinsmtp.routing.InboundRoutingPolicy
import io.github.kotlinsmtp.routing.MultiDomainRoutingPolicy
import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.server.SmtpServerRunner
import io.github.kotlinsmtp.spi.SmtpEventHook
import io.github.kotlinsmtp.spool.FileSpoolLockManager
import io.github.kotlinsmtp.spool.FileSpoolMetadataStore
import io.github.kotlinsmtp.spool.MailDeliveryService
import io.github.kotlinsmtp.spool.MailSpooler
import io.github.kotlinsmtp.spool.RedisSpoolLockManager
import io.github.kotlinsmtp.spool.RedisSpoolMetadataStore
import io.github.kotlinsmtp.spool.SpoolLockManager
import io.github.kotlinsmtp.spool.SpoolMetadataStore
import io.github.kotlinsmtp.storage.FileMessageStore
import io.github.kotlinsmtp.storage.FileSentMessageStore
import io.github.kotlinsmtp.storage.MessageStore
import io.github.kotlinsmtp.storage.SentMessageStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.stream.Collectors

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

    /**
     * 설정 파일 기반 인바운드 라우팅 정책.
     * 사용자가 커스텀 InboundRoutingPolicy 빈을 등록하면 대첵됩니다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun inboundRoutingPolicy(props: SmtpServerProperties): InboundRoutingPolicy {
        val domains = props.routing.effectiveLocalDomains()
        return MultiDomainRoutingPolicy(domains)
    }

    @Bean
    @ConditionalOnMissingBean
    fun localMailboxManager(props: SmtpServerProperties): LocalMailboxManager =
        LocalMailboxManager(props.storage.mailboxPath)

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun mailRelayDisabled(): MailRelay = object : MailRelay {
        override suspend fun relay(request: io.github.kotlinsmtp.relay.api.RelayRequest): io.github.kotlinsmtp.relay.api.RelayResult {
            throw SmtpSendResponse(550, "5.7.1 Relay access denied")
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun relayAccessPolicy(): RelayAccessPolicy = RelayDefaults.requireAuthPolicy()

    @Bean
    @ConditionalOnMissingBean
    fun mailDeliveryService(
        props: SmtpServerProperties,
        localMailboxManager: LocalMailboxManager,
        mailRelay: MailRelay,
        relayAccessPolicy: RelayAccessPolicy,
        dsnSenderProvider: ObjectProvider<DsnSender>,
        inboundRoutingPolicy: InboundRoutingPolicy,
    ): MailDeliveryService =
        MailDeliveryService(
            localMailboxManager = localMailboxManager,
            mailRelay = mailRelay,
            relayAccessPolicy = relayAccessPolicy,
            dsnSenderProvider = { dsnSenderProvider.getIfAvailable() },
            localDomain = inboundRoutingPolicy.localDomains().firstOrNull() ?: props.routing.localDomain,
        )

    @Bean
    @ConditionalOnMissingBean
    fun spoolMetadataStore(
        props: SmtpServerProperties,
        redisTemplateProvider: ObjectProvider<StringRedisTemplate>,
    ): SpoolMetadataStore =
        when (props.spool.type) {
            SmtpServerProperties.SpoolConfig.SpoolType.FILE -> FileSpoolMetadataStore(props.spool.path)
            SmtpServerProperties.SpoolConfig.SpoolType.REDIS -> {
                val redisTemplate = redisTemplateProvider.getIfAvailable()
                    ?: error("smtp.spool.type=redis requires StringRedisTemplate bean")
                RedisSpoolMetadataStore(
                    spoolDir = props.spool.path,
                    redisTemplate = redisTemplate,
                    keyPrefix = props.spool.redis.keyPrefix,
                    maxRawBytes = props.spool.redis.maxRawBytes,
                )
            }

            SmtpServerProperties.SpoolConfig.SpoolType.AUTO -> {
                val redisTemplate = redisTemplateProvider.getIfAvailable()
                if (redisTemplate != null) {
                    RedisSpoolMetadataStore(
                        spoolDir = props.spool.path,
                        redisTemplate = redisTemplate,
                        keyPrefix = props.spool.redis.keyPrefix,
                        maxRawBytes = props.spool.redis.maxRawBytes,
                    )
                } else {
                    FileSpoolMetadataStore(props.spool.path)
                }
            }
        }

    /**
     * 설정에 따라 스풀 락 관리자를 생성합니다.
     *
     * @param props SMTP 서버 설정
     * @param redisTemplateProvider Redis 템플릿 제공자
     * @return 파일/Redis 기반 스풀 락 관리자
     */
    @Bean
    @ConditionalOnMissingBean
    fun spoolLockManager(
        props: SmtpServerProperties,
        redisTemplateProvider: ObjectProvider<StringRedisTemplate>,
    ): SpoolLockManager =
        when (props.spool.type) {
            SmtpServerProperties.SpoolConfig.SpoolType.FILE -> FileSpoolLockManager(
                spoolDir = props.spool.path,
                staleLockThreshold = java.time.Duration.ofMinutes(15),
            )

            SmtpServerProperties.SpoolConfig.SpoolType.REDIS -> {
                val redisTemplate = redisTemplateProvider.getIfAvailable()
                    ?: error("smtp.spool.type=redis requires StringRedisTemplate bean")
                RedisSpoolLockManager(
                    redisTemplate = redisTemplate,
                    keyPrefix = props.spool.redis.keyPrefix,
                    lockTtl = java.time.Duration.ofSeconds(props.spool.redis.lockTtlSeconds),
                )
            }

            SmtpServerProperties.SpoolConfig.SpoolType.AUTO -> {
                val redisTemplate = redisTemplateProvider.getIfAvailable()
                if (redisTemplate != null) {
                    RedisSpoolLockManager(
                        redisTemplate = redisTemplate,
                        keyPrefix = props.spool.redis.keyPrefix,
                        lockTtl = java.time.Duration.ofSeconds(props.spool.redis.lockTtlSeconds),
                    )
                } else {
                    FileSpoolLockManager(
                        spoolDir = props.spool.path,
                        staleLockThreshold = java.time.Duration.ofMinutes(15),
                    )
                }
            }
        }

    @Bean
    @ConditionalOnMissingBean
    fun mailSpooler(
        props: SmtpServerProperties,
        deliveryService: MailDeliveryService,
        dsnSenderProvider: ObjectProvider<DsnSender>,
        spoolMetrics: SpoolMetrics,
        metadataStore: SpoolMetadataStore,
        lockManager: SpoolLockManager,
    ): MailSpooler =
        MailSpooler(
            spoolDir = props.spool.path,
            maxRetries = props.spool.maxRetries,
            retryDelaySeconds = props.spool.retryDelaySeconds,
            deliveryService = deliveryService,
            dsnSenderProvider = { dsnSenderProvider.getIfAvailable() },
            spoolMetrics = spoolMetrics,
            injectedMetadataStore = metadataStore,
            injectedLockManager = lockManager,
        )

    @Bean
    @ConditionalOnMissingBean
    fun spoolMetrics(): SpoolMetrics = SpoolMetrics.NOOP

    /**
     * Micrometer 레지스트리가 있을 때 스풀 메트릭 구현을 활성화합니다.
     *
     * @param meterRegistry Micrometer 메트릭 레지스트리
     * @return Micrometer 기반 스풀 메트릭 기록기
     */
    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(SpoolMetrics::class)
    fun micrometerSpoolMetrics(meterRegistry: MeterRegistry): SpoolMetrics =
        MicrometerSpoolMetrics(meterRegistry)

    /**
     * Micrometer 레지스트리가 있을 때 SMTP 이벤트 메트릭 훅을 등록합니다.
     *
     * @param meterRegistry Micrometer 메트릭 레지스트리
     * @return SMTP 이벤트를 계측하는 훅 구현
     */
    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(MicrometerSmtpEventHook::class)
    fun micrometerSmtpEventHook(meterRegistry: MeterRegistry): SmtpEventHook =
        MicrometerSmtpEventHook(meterRegistry)

    @Bean
    @ConditionalOnMissingBean
    fun dsnStore(spooler: MailSpooler): DsnStore =
        DsnStore { rawMessagePath, envelopeSender, recipients, messageId, authenticated, dsnRet, dsnEnvid, rcptDsn ->
            spooler.enqueue(
                rawMessagePath = rawMessagePath,
                sender = envelopeSender,
                recipients = recipients,
                messageId = messageId,
                authenticated = authenticated,
                dsnRet = dsnRet,
                dsnEnvid = dsnEnvid,
                rcptDsn = rcptDsn,
            )
        }

    @Bean
    @ConditionalOnMissingBean
    fun messageStore(props: SmtpServerProperties): MessageStore =
    // 기능 우선: 파일 기반 임시 저장
        // TODO(storage): DB/S3 등으로 교체 시 구현체만 바꾸도록 경계를 유지합니다.
        FileMessageStore(tempDir = props.storage.tempPath)

    /**
     * 기본 보낸 메일함 저장소를 파일 기반으로 제공합니다.
     *
     * @param props SMTP 서버 설정
     * @return 파일 기반 보낸 메일함 저장소
     */
    @Bean
    @ConditionalOnMissingBean
    fun sentMessageStore(props: SmtpServerProperties): SentMessageStore =
        FileSentMessageStore(mailboxDir = props.storage.mailboxPath)

    @Bean
    @ConditionalOnMissingBean
    fun smtpUserHandler(
        props: SmtpServerProperties,
        inboundRoutingPolicy: InboundRoutingPolicy,
    ): SmtpUserHandler =
        LocalDirectoryUserHandler(
            mailboxDir = props.storage.mailboxPath,
            localDomain = inboundRoutingPolicy.localDomains().firstOrNull() ?: props.routing.localDomain,
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
        sentMessageStore: SentMessageStore,
        authService: AuthService,
        eventHooksProvider: ObjectProvider<SmtpEventHook>,
    ): List<SmtpServer> {
        // Validate all configuration properties (throws on invalid config)
        props.validate()

        val cert = if (props.ssl.enabled) props.ssl.getCertChainFile() else null
        val key = if (props.ssl.enabled) props.ssl.getPrivateKeyFile() else null

        val handlerCreator = {
            SimpleSmtpProtocolHandler(
                messageStore = messageStore,
                sentMessageStore = sentMessageStore,
                sentArchiveMode = props.sentArchive.mode,
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

        // SMTPS(implicit TLS) 리스너는 TLS 설정이 없으면 정상 동작할 수 없으므로 fail-fast 합니다.
        if (effectiveListeners.any { it.implicitTls }) {
            require(props.ssl.enabled && cert != null && key != null) {
                "smtp.ssl.enabled=true and valid ssl.certChainFile/privateKeyFile are required when any listener uses implicitTls=true"
            }
        }

        // Spring Boot starter 안정성:
        // - 훅 Bean이 아예 없어도(0개) 부팅이 실패하지 않도록 ObjectProvider로 받습니다.
        // - @Order / Ordered가 붙은 훅은 orderedStream()으로 순서를 반영합니다.
        val eventHooks = eventHooksProvider.orderedStream().collect(Collectors.toList())

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
                listener.idleTimeoutSeconds = l.idleTimeoutSeconds

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
