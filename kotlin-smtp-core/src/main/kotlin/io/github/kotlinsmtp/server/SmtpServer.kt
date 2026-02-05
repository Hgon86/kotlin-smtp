package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.auth.AuthRateLimiter
import io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler
import io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import io.github.kotlinsmtp.protocol.handler.SmtpUserHandler
import io.github.kotlinsmtp.spi.SmtpEventHook
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.CharsetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private val log = KotlinLogging.logger {}

public class SmtpServer internal constructor(
    public val port: Int,
    public val hostname: String,
    public val serviceName: String? = "kotlin-smtp",
    internal val authService: AuthService? = null,
    internal val transactionHandlerCreator: (() -> SmtpProtocolHandler)? = null,
    internal val userHandler: SmtpUserHandler? = null,
    internal val mailingListHandler: SmtpMailingListHandler? = null,
    internal val spooler: SmtpSpooler? = null,
    internal val eventHooks: List<SmtpEventHook> = emptyList(),
    internal val authRateLimiter: AuthRateLimiter? = null,
    internal val enableVrfy: Boolean = false,
    internal val enableEtrn: Boolean = false,
    internal val enableExpn: Boolean = false,
    internal val implicitTls: Boolean = false, // 465(SMTPS)처럼 접속 즉시 TLS
    internal val enableStartTls: Boolean = true, // STARTTLS 커맨드/광고 허용
    internal val enableAuth: Boolean = true, // AUTH 커맨드/광고 허용
    internal val requireAuthForMail: Boolean = false, // MAIL 트랜잭션 시작 전 AUTH 강제(Submission 용도)
    // PROXY protocol(v1) 지원 (HAProxy 등 L4 프록시 뒤에서 원본 클라이언트 IP 복원)
    internal val proxyProtocolEnabled: Boolean = false,
    internal val trustedProxyCidrs: List<String> = listOf("127.0.0.1/32", "::1/128"),
    private val certChainFile: File? = null,
    private val privateKeyFile: File? = null,
    // TLS 하드닝 설정
    private val minTlsVersion: String = "TLSv1.2",
    private val tlsHandshakeTimeoutMs: Int = 30_000,
    private val tlsCipherSuites: List<String> = emptyList(),
    maxConnectionsPerIp: Int = 10,
    maxMessagesPerIpPerHour: Int = 100,
) {
    internal var serverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverMutex = Mutex()
    private var channelFuture: ChannelFuture? = null
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null

    // Rate Limiter (스팸 및 DoS 방지)
    internal val rateLimiter = RateLimiter(maxConnectionsPerIp, maxMessagesPerIpPerHour)

    // 활성 세션 추적 (graceful shutdown용)
    internal val sessionTracker = ActiveSessionTracker()

    internal suspend fun notifyHooks(block: suspend (SmtpEventHook) -> Unit) {
        if (eventHooks.isEmpty()) return
        for (hook in eventHooks) {
            runCatching { block(hook) }
                .onFailure { t ->
                    log.warn(t) { "SmtpEventHook failed: ${hook::class.qualifiedName ?: hook::class.simpleName}" }
                }
        }
    }

    // 신뢰 프록시 CIDR 파싱(런타임 오버헤드 최소화)
    internal val trustedProxyCidrsParsed = trustedProxyCidrs.mapNotNull { io.github.kotlinsmtp.utils.IpCidr.parse(it) }

    @Volatile
    private var currentSslContext: SslContext? = buildSslContext()

    internal val sslContext: SslContext?
        get() = currentSslContext

    // TLS 하드닝 설정 접근자 (SmtpSession 등에서 사용)
    internal val tlsHandshakeTimeout: Long = tlsHandshakeTimeoutMs.toLong()

    /** 서버 실행 상태를 추적합니다. */
    private enum class LifecycleState {
        STOPPED,
        RUNNING,
    }

    @Volatile
    private var state: LifecycleState = LifecycleState.STOPPED

    private fun buildSslContext(): SslContext? {
        if (certChainFile != null && privateKeyFile != null) {
            return runCatching {
                val builder = SslContextBuilder.forServer(certChainFile, privateKeyFile)

                // 최소 TLS 버전 설정 (TLSv1.2 권장)
                val protocols = when (minTlsVersion.uppercase()) {
                    "TLSV1.3", "TLSv1.3" -> arrayOf("TLSv1.3", "TLSv1.2")
                    "TLSV1.2", "TLSv1.2" -> arrayOf("TLSv1.2")
                    else -> arrayOf(minTlsVersion)
                }
                builder.protocols(*protocols)

                // 암호 스위트 설정 (지정된 경우)
                if (tlsCipherSuites.isNotEmpty()) {
                    builder.ciphers(tlsCipherSuites)
                }

                builder.build()
            }
                .onFailure { log.error(it) { "Failed to initialize SSL context" } }
                .getOrNull()
        }
        return null
    }

    private fun scheduleCertificateReload() {
        if (certChainFile == null || privateKeyFile == null) return
        serverScope.launch {
            var lastChainTime = fileTimestamp(certChainFile)
            var lastKeyTime = fileTimestamp(privateKeyFile)
            while (serverScope.isActive) {
                kotlinx.coroutines.delay(Duration.ofMinutes(5).toMillis())
                val currentChainTime = fileTimestamp(certChainFile)
                val currentKeyTime = fileTimestamp(privateKeyFile)
                if (currentChainTime > lastChainTime || currentKeyTime > lastKeyTime) {
                    log.info { "Detected TLS certificate change, reloading." }
                    buildSslContext()?.let { newContext ->
                        currentSslContext = newContext
                        log.info { "TLS context reloaded." }
                    }
                    lastChainTime = currentChainTime
                    lastKeyTime = currentKeyTime
                }
            }
        }
    }

    private fun scheduleRateLimiterCleanup() {
        serverScope.launch {
            while (serverScope.isActive) {
                kotlinx.coroutines.delay(Duration.ofHours(1).toMillis())
                rateLimiter.cleanup()
                authRateLimiter?.cleanup()
                log.debug { "Rate limiter cleanup completed" }
            }
        }
    }

    private fun fileTimestamp(file: File): Long = runCatching { Files.getLastModifiedTime(file.toPath()).toMillis() }.getOrDefault(0L)

    private fun ensureRuntime() {
        // If the scope was cancelled by a previous stop(), recreate it.
        if (!serverScope.isActive) {
            serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        val boss = bossGroup
        val worker = workerGroup

        if (boss == null || boss.isShuttingDown || boss.isShutdown || boss.isTerminated) {
            bossGroup = NioEventLoopGroup(1)
        }
        if (worker == null || worker.isShuttingDown || worker.isShutdown || worker.isTerminated) {
            workerGroup = NioEventLoopGroup()
        }

        // Start background maintenance tasks for this runtime.
        scheduleCertificateReload()
        scheduleRateLimiterCleanup()
    }

    /**
     * SMTP 서버를 시작합니다.
     *
     * @param wait true인 경우 서버 채널이 닫힐 때까지 반환하지 않습니다.
     * @return 이미 실행 중이면 false, 새로 시작하면 true
     */
    public suspend fun start(wait: Boolean = false): Boolean {
        var closeFutureToWait: io.netty.channel.ChannelFuture? = null
        val started = serverMutex.withLock {
            if (state == LifecycleState.RUNNING || channelFuture != null) return@withLock false

            ensureRuntime()

            val bootstrap = ServerBootstrap()
            bootstrap.group(
                bossGroup ?: error("bossGroup must be initialized"),
                workerGroup ?: error("workerGroup must be initialized")
            )
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        // 파이프라인 핸들러에 이름을 명시해 STARTTLS 업그레이드/디버깅을 단순화합니다.
                        val p = ch.pipeline()

                        // PROXY protocol(v1):
                        // - 프록시가 "원본 클라이언트 IP" 정보를 접속 직후 한 줄로 먼저 전달합니다.
                        // - implicit TLS(465)에서도 PROXY 라인이 TLS 핸드셰이크보다 먼저 오므로,
                        //   반드시 SSL 핸들러보다 앞에서 디코딩해야 합니다.
                        if (proxyProtocolEnabled) {
                            p.addLast("proxyDecoder", HAProxyMessageDecoder())
                        }

                        // SMTPS(implicit TLS): 접속 즉시 TLS 핸드셰이크를 시작합니다.
                        // - sslContext가 없으면 잘못된 설정이므로 즉시 연결을 종료합니다.
                        if (implicitTls) {
                            val ctx = sslContext
                            if (ctx == null) {
                                ch.close()
                                return
                            }
                            val ssl = ctx.newHandler(ch.alloc()).also {
                                it.engine().useClientMode = false
                                it.setHandshakeTimeout(tlsHandshakeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                            }
                            p.addLast("ssl", ssl)
                        }

                        // SMTP 입력 프레이밍(라인/BDAT 바이트)을 자체 처리합니다.
                        // - LineBasedFrameDecoder/StringDecoder 조합은 BDAT(CHUNKING) 구현이 불가능합니다.
                        p.addLast("smtpInboundDecoder", SmtpInboundDecoder())

                        // SMTP 응답은 8BITMIME를 고려해 ISO-8859-1로 인코딩합니다.
                        // (SMTPUTF8는 별도 확장으로 다룸)
                        p.addLast("stringEncoder", StringEncoder(CharsetUtil.ISO_8859_1))
                        p.addLast("idleStateHandler", IdleStateHandler(0, 0, 5, java.util.concurrent.TimeUnit.MINUTES))
                        p.addLast("smtpChannelHandler", SmtpChannelHandler(this@SmtpServer))
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

            channelFuture = bootstrap.bind(port).sync()
            state = LifecycleState.RUNNING
            log.info { "Started smtp server, listening on port $port" }

            if (wait) {
                closeFutureToWait = channelFuture?.channel()?.closeFuture()
            }

            true
        }

        if (!started) return false

        if (wait) closeFutureToWait?.sync()

        return true
    }

    /**
     * SMTP 서버를 종료합니다.
     *
     * - 신규 연결 accept를 중단합니다.
     * - 활성 세션에 close를 요청하고, 지정된 시간 내에 드레인(drain)합니다.
     * - Netty 이벤트 루프 종료까지 최대한 기다립니다.
     *
     * @param gracefulTimeoutMs graceful shutdown에 사용할 전체 타임아웃(ms)
     * @return 실행 중인 서버를 종료했으면 true, 이미 종료 상태면 false
     */
    public suspend fun stop(gracefulTimeoutMs: Long = 30000): Boolean = serverMutex.withLock {
        if (state == LifecycleState.RUNNING && channelFuture != null) {
            try {
                log.info { "Initiating graceful shutdown (port=$port)" }

                val deadline = System.currentTimeMillis() + gracefulTimeoutMs

                // 1. Stop accepting new connections
                channelFuture?.channel()?.close()?.sync()
                log.info { "Stopped accepting new connections" }

                // 2. Close all active sessions
                sessionTracker.closeAllSessions()

                // 3. Wait for sessions to close gracefully (bounded)
                fun remainingMs(): Long = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)

                var allClosed = sessionTracker.awaitAllSessionsClosed(remainingMs())
                if (!allClosed && remainingMs() > 0) {
                    log.warn { "Graceful shutdown timeout. Retrying session close for remaining sessions" }
                    sessionTracker.closeAllSessions()
                    allClosed = sessionTracker.awaitAllSessionsClosed(remainingMs())
                }

                // 4. Shutdown event loops
                val worker = workerGroup
                val boss = bossGroup

                worker?.shutdownGracefully(0, remainingMs().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                boss?.shutdownGracefully(0, remainingMs().coerceAtLeast(1), TimeUnit.MILLISECONDS)

                // Netty user guide recommends awaiting terminationFuture().
                val workerDone = worker?.terminationFuture()?.await(remainingMs().coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: true
                val bossDone = boss?.terminationFuture()?.await(remainingMs().coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: true
                if (!workerDone || !bossDone) {
                    log.warn { "Event loop groups did not terminate within timeout (port=$port)" }
                }

                // Cancel background tasks last.
                serverScope.cancel()

                log.info { "SMTP server stopped (port=$port, sessionsClosed=$allClosed)" }
                true
            } finally {
                channelFuture = null
                state = LifecycleState.STOPPED
                bossGroup = null
                workerGroup = null
            }
        } else false
    }

    public companion object {
        /**
         * Preferred entrypoint for constructing an SMTP server.
         *
         * This enforces the public API boundary by keeping the implementation constructor internal.
         */
        @JvmStatic
        public fun builder(port: Int, hostname: String): Builder = Builder(port, hostname)

        /**
         * Kotlin-friendly factory.
         */
        @JvmStatic
        public fun create(port: Int, hostname: String, configure: Builder.() -> Unit): SmtpServer =
            builder(port, hostname).apply(configure).build()
    }

    public class Builder internal constructor(
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
         * 엔진 이벤트 훅(SPI)을 추가합니다.
         *
         * - 훅 예외는 기본적으로 Non-fatal이며, 서버 처리는 계속됩니다.
         *
         * @param hook 등록할 훅
         */
        public fun addEventHook(hook: SmtpEventHook): Unit {
            eventHooks.add(hook)
        }

        public val features: FeatureFlags = FeatureFlags()
        public val listener: ListenerPolicy = ListenerPolicy()
        public val proxyProtocol: ProxyProtocolPolicy = ProxyProtocolPolicy()
        public val tls: TlsPolicy = TlsPolicy()
        public val rateLimit: RateLimitPolicy = RateLimitPolicy()
        public val authRateLimit: AuthRateLimitPolicy = AuthRateLimitPolicy()

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
            )
        }
    }

    /**
     * 서버 기능 플래그 모음입니다.
     *
     * @property enableVrfy VRFY 커맨드 활성화
     * @property enableEtrn ETRN 커맨드 활성화
     * @property enableExpn EXPN 커맨드 활성화
     */
    public class FeatureFlags internal constructor() {
        public var enableVrfy: Boolean = false
        public var enableEtrn: Boolean = false
        public var enableExpn: Boolean = false
    }

    /**
     * 리스너(접속 포트)의 동작 정책입니다.
     *
     * @property implicitTls 접속 즉시 TLS(465; SMTPS) 여부
     * @property enableStartTls STARTTLS 커맨드/광고 허용 여부
     * @property enableAuth AUTH 커맨드/광고 허용 여부
     * @property requireAuthForMail MAIL 트랜잭션 시작 전 AUTH 강제 여부
     */
    public class ListenerPolicy internal constructor() {
        public var implicitTls: Boolean = false
        public var enableStartTls: Boolean = true
        public var enableAuth: Boolean = true
        public var requireAuthForMail: Boolean = false
    }

    /**
     * PROXY protocol(v1) 설정입니다.
     *
     * @property enabled PROXY protocol 수신 여부
     * @property trustedProxyCidrs 신뢰 프록시 CIDR 목록
     */
    public class ProxyProtocolPolicy internal constructor() {
        public var enabled: Boolean = false
        public var trustedProxyCidrs: List<String> = listOf("127.0.0.1/32", "::1/128")
    }

    /**
     * TLS 설정입니다.
     *
     * @property certChainPath 인증서 체인 경로
     * @property privateKeyPath 개인키 경로
     * @property minTlsVersion 최소 TLS 버전
     * @property handshakeTimeoutMs TLS 핸드셰이크 타임아웃(ms)
     * @property cipherSuites 허용 cipher suites(지정 시)
     */
    public class TlsPolicy internal constructor() {
        public var certChainPath: java.nio.file.Path? = null
        public var privateKeyPath: java.nio.file.Path? = null
        public var minTlsVersion: String = "TLSv1.2"
        public var handshakeTimeoutMs: Int = 30_000
        public var cipherSuites: List<String> = emptyList()
    }

    /**
     * 연결/메시지 Rate Limit 설정입니다.
     *
     * @property maxConnectionsPerIp IP당 최대 동시 연결 수
     * @property maxMessagesPerIpPerHour IP당 시간당 최대 메시지 수
     */
    public class RateLimitPolicy internal constructor() {
        public var maxConnectionsPerIp: Int = 10
        public var maxMessagesPerIpPerHour: Int = 100
    }

    /**
     * 인증(AUTH) Rate Limit 설정입니다.
     *
     * @property enabled 인증 rate limit 사용 여부
     * @property maxFailuresPerWindow 윈도우 내 최대 실패 횟수
     * @property windowSeconds 윈도우 크기(초)
     * @property lockoutDurationSeconds 잠금 지속 시간(초)
     */
    public class AuthRateLimitPolicy internal constructor() {
        public var enabled: Boolean = true
        public var maxFailuresPerWindow: Int = 5
        public var windowSeconds: Long = 300
        public var lockoutDurationSeconds: Long = 600
    }
}
