package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import io.github.kotlinsmtp.protocol.handler.SmtpUserHandler
import io.github.kotlinsmtp.spi.SmtpEventHook

/**
 * SMTP server builder.
 *
 * @property port Binding port
 * @property hostname Server hostname
 */
public class SmtpServerBuilder internal constructor(
    private val port: Int,
    private val hostname: String,
) {
    public var serviceName: String? = "kotlin-smtp"

    /** Required: provides the per-session protocol handler. */
    private var protocolHandlerFactory: (() -> SmtpProtocolHandler)? = null

    private var authService: AuthService? = null
    private var userHandler: SmtpUserHandler? = null
    private var mailingListHandler: SmtpMailingListHandler? = null
    private var spooler: SmtpSpooler? = null

    private val eventHooks: MutableList<SmtpEventHook> = mutableListOf()

    public fun useProtocolHandlerFactory(factory: () -> SmtpProtocolHandler): Unit {
        this.protocolHandlerFactory = factory
    }

    public fun useAuthService(service: AuthService?): Unit {
        this.authService = service
    }

    public fun useUserHandler(handler: SmtpUserHandler?): Unit {
        this.userHandler = handler
    }

    public fun useMailingListHandler(handler: SmtpMailingListHandler?): Unit {
        this.mailingListHandler = handler
    }

    public fun useSpooler(spooler: SmtpSpooler?): Unit {
        this.spooler = spooler
    }

    /**
     * Add engine event hook (SPI).
     *
     * - Hook exceptions are non-fatal by default, and server processing continues.
     *
     * @param hook Hook to register
     */
    public fun addEventHook(hook: SmtpEventHook): Unit {
        eventHooks.add(hook)
    }

    public val features: SmtpFeatureFlags = SmtpFeatureFlags()
    public val listener: SmtpListenerPolicy = SmtpListenerPolicy()
    public val proxyProtocol: SmtpProxyProtocolPolicy = SmtpProxyProtocolPolicy()
    public val tls: SmtpTlsPolicy = SmtpTlsPolicy()
    public val rateLimit: SmtpRateLimitPolicy = SmtpRateLimitPolicy()
    public val authRateLimit: SmtpAuthRateLimitPolicy = SmtpAuthRateLimitPolicy()

    public fun build(): SmtpServer {
        val handlerFactory = protocolHandlerFactory
            ?: error("protocolHandlerFactory is required. Call useProtocolHandlerFactory { }.")

        val authLimiter = if (authRateLimit.enabled) {
            io.github.kotlinsmtp.auth.AuthRateLimiter(
                maxFailuresPerWindow = authRateLimit.maxFailuresPerWindow,
                windowSeconds = authRateLimit.windowSeconds,
                lockoutDurationSeconds = authRateLimit.lockoutDurationSeconds,
            )
        } else {
            null
        }

        return SmtpServer(
            port = port,
            hostname = hostname,
            serviceName = serviceName,
            authService = authService,
            transactionHandlerCreator = handlerFactory,
            userHandler = userHandler,
            mailingListHandler = mailingListHandler,
            spooler = spooler,
            eventHooks = eventHooks.toList(),
            authRateLimiter = authLimiter,
            enableVrfy = features.enableVrfy,
            enableEtrn = features.enableEtrn,
            enableExpn = features.enableExpn,
            implicitTls = listener.implicitTls,
            enableStartTls = listener.enableStartTls,
            enableAuth = listener.enableAuth,
            requireAuthForMail = listener.requireAuthForMail,
            proxyProtocolEnabled = proxyProtocol.enabled,
            trustedProxyCidrs = proxyProtocol.trustedProxyCidrs,
            certChainFile = tls.certChainPath?.toFile(),
            privateKeyFile = tls.privateKeyPath?.toFile(),
            minTlsVersion = tls.minTlsVersion,
            tlsHandshakeTimeoutMs = tls.handshakeTimeoutMs,
            tlsCipherSuites = tls.cipherSuites,
            maxConnectionsPerIp = rateLimit.maxConnectionsPerIp,
            maxMessagesPerIpPerHour = rateLimit.maxMessagesPerIpPerHour,
            idleTimeoutSeconds = listener.idleTimeoutSeconds,
        )
    }
}
