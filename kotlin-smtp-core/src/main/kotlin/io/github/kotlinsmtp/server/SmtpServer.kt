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
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
    internal val idleTimeoutSeconds: Int = 300, // 5분 (0이면 타임아웃 없음)
) {
    internal var serverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverMutex = Mutex()
    private var channelFuture: ChannelFuture? = null
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var maintenanceScheduler: SmtpServerMaintenanceScheduler? = null

    // Rate Limiter (스팸 및 DoS 방지)
    internal val rateLimiter = RateLimiter(maxConnectionsPerIp, maxMessagesPerIpPerHour)

    // 활성 세션 추적 (graceful shutdown용)
    internal val sessionTracker = ActiveSessionTracker()

    internal fun hasEventHooks(): Boolean = eventHooks.isNotEmpty()

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

    private fun startMaintenanceTasks() {
        maintenanceScheduler?.stop()
        maintenanceScheduler = SmtpServerMaintenanceScheduler(
            scope = serverScope,
            certChainFile = certChainFile,
            privateKeyFile = privateKeyFile,
            onCertificateChanged = {
                buildSslContext()?.let { newContext ->
                    currentSslContext = newContext
                    log.info { "TLS context reloaded." }
                }
            },
            onRateLimiterCleanup = {
                rateLimiter.cleanup()
                authRateLimiter?.cleanup()
            },
        ).also { it.start() }
    }

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
        startMaintenanceTasks()
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

            val pipelineConfigurator = SmtpServerPipelineConfigurator(
                server = this,
                tlsHandshakeTimeoutMs = tlsHandshakeTimeoutMs,
                idleTimeoutSeconds = idleTimeoutSeconds,
            )

            val bootstrap = ServerBootstrap()
            bootstrap.group(
                bossGroup ?: error("bossGroup must be initialized"),
                workerGroup ?: error("workerGroup must be initialized")
            )
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        pipelineConfigurator.configure(ch)
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
                maintenanceScheduler?.stop()
                maintenanceScheduler = null
                serverScope.cancel()

                log.info { "SMTP server stopped (port=$port, sessionsClosed=$allClosed)" }
                true
            } finally {
                channelFuture = null
                state = LifecycleState.STOPPED
                bossGroup = null
                workerGroup = null
                maintenanceScheduler?.stop()
                maintenanceScheduler = null
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
        public fun builder(port: Int, hostname: String): SmtpServerBuilder = SmtpServerBuilder(port, hostname)

        /**
         * Kotlin-friendly factory.
         */
        @JvmStatic
        public fun create(port: Int, hostname: String, configure: SmtpServerBuilder.() -> Unit): SmtpServer =
            builder(port, hostname).apply(configure).build()
    }
}
