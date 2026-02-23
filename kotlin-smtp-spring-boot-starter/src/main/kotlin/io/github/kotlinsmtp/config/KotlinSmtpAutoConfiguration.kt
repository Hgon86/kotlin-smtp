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
import io.github.kotlinsmtp.ratelimit.RedisSmtpAuthRateLimiter
import io.github.kotlinsmtp.ratelimit.RedisSmtpRateLimiter
import io.github.kotlinsmtp.server.SmtpRateLimiter
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
import io.github.kotlinsmtp.auth.SmtpAuthRateLimiter
import java.util.stream.Collectors

@AutoConfiguration
@EnableConfigurationProperties(SmtpServerProperties::class)
class KotlinSmtpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun authService(props: SmtpServerProperties): AuthService = InMemoryAuthService(
        enabled = props.auth.enabled,
        required = props.auth.required,
        users = props.auth.users,
        allowPlaintextPasswords = props.auth.allowPlaintextPasswords,
    )

    /**
     * Configuration-based inbound routing policy.
     * Replaced when a custom `InboundRoutingPolicy` bean is registered by the user.
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
     * Creates a spool lock manager based on configuration.
     *
     * @param props SMTP server properties
     * @param redisTemplateProvider Redis template provider
     * @return file-based or Redis-based spool lock manager
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
            workerConcurrency = props.spool.workerConcurrency,
            triggerCooldownMillis = props.spool.triggerCooldownMillis,
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
     * Enables Micrometer spool metrics when a registry is available.
     *
     * @param meterRegistry Micrometer metric registry
     * @return Micrometer-backed spool metrics recorder
     */
    @Bean
    @ConditionalOnClass(MeterRegistry::class)
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(SpoolMetrics::class)
    fun micrometerSpoolMetrics(meterRegistry: MeterRegistry): SpoolMetrics =
        MicrometerSpoolMetrics(meterRegistry)

    /**
     * Registers SMTP event metric hook when a Micrometer registry is available.
     *
     * @param meterRegistry Micrometer metric registry
     * @return hook implementation that instruments SMTP events
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
    // Feature-first: file-based temporary storage.
        // TODO(storage): keep boundary so only implementation changes for DB/S3.
        FileMessageStore(tempDir = props.storage.tempPath)

    /**
     * Provides file-based Sent mailbox storage by default.
     *
     * @param props SMTP server properties
     * @return file-based Sent mailbox storage
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
        // TODO(DB/MSA): in production, migrate mailing lists/membership to DB or directory service.
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
        smtpRateLimiterProvider: ObjectProvider<SmtpRateLimiter>,
        smtpAuthRateLimiterProvider: ObjectProvider<SmtpAuthRateLimiter>,
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
            // Backward compatibility: single-listener mode using only smtp.port.
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

        // Fail fast when SMTPS (implicit TLS) listeners are configured without TLS settings.
        if (effectiveListeners.any { it.implicitTls }) {
            require(props.ssl.enabled && cert != null && key != null) {
                "smtp.ssl.enabled=true and valid ssl.certChainFile/privateKeyFile are required when any listener uses implicitTls=true"
            }
        }

        // Spring Boot starter resilience:
        // - Use ObjectProvider so startup succeeds even with zero hook beans.
        // - Respect @Order / Ordered using orderedStream().
        val eventHooks = eventHooksProvider.orderedStream().collect(Collectors.toList())

        return effectiveListeners.map { l ->
            SmtpServer.create(l.port, props.hostname) {
                serviceName = l.serviceName ?: props.serviceName
                useAuthService(authService)
                useProtocolHandlerFactory(handlerCreator)
                useUserHandler(userHandler)
                useMailingListHandler(mailingListHandler)
                useSpooler(spooler)

                // SPI hooks: bridge core events to external infrastructure (S3/Kafka/DB, etc.).
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

                smtpRateLimiterProvider.getIfAvailable()?.let { limiter ->
                    useRateLimiter(limiter)
                }
                if (props.auth.rateLimitEnabled) {
                    smtpAuthRateLimiterProvider.getIfAvailable()?.let { limiter ->
                        useAuthRateLimiter(limiter)
                    }
                }
            }
        }
    }

    /**
     * Creates a distributed connection/message rate limiter when Redis backend is selected.
     */
    @Bean
    @ConditionalOnProperty(prefix = "smtp.rateLimit", name = ["backend"], havingValue = "redis")
    @ConditionalOnMissingBean(SmtpRateLimiter::class)
    fun smtpRateLimiter(
        props: SmtpServerProperties,
        redisTemplateProvider: ObjectProvider<StringRedisTemplate>,
    ): SmtpRateLimiter {
        val redisTemplate = redisTemplateProvider.getIfAvailable()
            ?: error("smtp.rateLimit.backend=redis requires StringRedisTemplate bean")
        return RedisSmtpRateLimiter(
            redisTemplate = redisTemplate,
            keyPrefix = props.rateLimit.redis.keyPrefix,
            maxConnectionsPerIp = props.rateLimit.maxConnectionsPerIp,
            maxMessagesPerIpPerHour = props.rateLimit.maxMessagesPerIpPerHour,
            connectionCounterTtl = java.time.Duration.ofSeconds(props.rateLimit.redis.connectionCounterTtlSeconds),
        )
    }

    /**
     * Creates a distributed AUTH rate limiter when Redis backend is selected.
     */
    @Bean
    @ConditionalOnProperty(prefix = "smtp.auth", name = ["rateLimitBackend"], havingValue = "redis")
    @ConditionalOnMissingBean(SmtpAuthRateLimiter::class)
    fun smtpAuthRateLimiter(
        props: SmtpServerProperties,
        redisTemplateProvider: ObjectProvider<StringRedisTemplate>,
    ): SmtpAuthRateLimiter {
        if (!props.auth.rateLimitEnabled) {
            return object : SmtpAuthRateLimiter {
                override fun checkLock(clientIp: String?, username: String): Long? = null
                override fun recordFailure(clientIp: String?, username: String): Boolean = false
                override fun recordSuccess(clientIp: String?, username: String) = Unit
                override fun cleanup() = Unit
            }
        }
        val redisTemplate = redisTemplateProvider.getIfAvailable()
            ?: error("smtp.auth.rateLimitBackend=redis requires StringRedisTemplate bean")
        return RedisSmtpAuthRateLimiter(
            redisTemplate = redisTemplate,
            keyPrefix = props.auth.rateLimitRedis.keyPrefix,
            maxFailuresPerWindow = props.auth.rateLimitMaxFailures,
            windowSeconds = props.auth.rateLimitWindowSeconds,
            lockoutDurationSeconds = props.auth.rateLimitLockoutSeconds,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun smtpServerRunner(props: SmtpServerProperties, smtpServers: List<SmtpServer>): SmtpServerRunner =
        SmtpServerRunner(
            smtpServers = smtpServers,
            gracefulShutdownTimeoutMs = props.lifecycle.gracefulShutdownTimeoutMs,
        )
}
