package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.auth.AuthRateLimiter
import io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler
import io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import io.github.kotlinsmtp.protocol.handler.SmtpUserHandler
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
import io.netty.handler.ssl.SslHandler
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
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.CoroutineContext

private val log = KotlinLogging.logger {}

class SmtpServer(
    val port: Int,
    val hostname: String,
    val serviceName: String? = "kotlin-smtp",
    internal val authService: AuthService? = null,
    internal val transactionHandlerCreator: (() -> SmtpProtocolHandler)? = null,
    internal val userHandler: SmtpUserHandler? = null,
    internal val mailingListHandler: SmtpMailingListHandler? = null,
    internal val spooler: SmtpSpooler? = null,
    internal val authRateLimiter: AuthRateLimiter? = null,
    // 기능 플래그(기본값은 인터넷 노출 기준으로 보수적으로 off)
    internal val enableVrfy: Boolean = false,
    internal val enableEtrn: Boolean = false,
    internal val enableExpn: Boolean = false,
    // 리스너(포트)별 정책 플래그
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
    internal val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverMutex = Mutex()
    private var channelFuture: ChannelFuture? = null
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    // Rate Limiter (스팸 및 DoS 방지)
    internal val rateLimiter = RateLimiter(maxConnectionsPerIp, maxMessagesPerIpPerHour)

    // 활성 세션 추적 (graceful shutdown용)
    internal val sessionTracker = ActiveSessionTracker()

    // 신뢰 프록시 CIDR 파싱(런타임 오버헤드 최소화)
    internal val trustedProxyCidrsParsed = trustedProxyCidrs.mapNotNull { io.github.kotlinsmtp.utils.IpCidr.parse(it) }

    @Volatile
    private var currentSslContext: SslContext? = buildSslContext()

    internal val sslContext: SslContext?
        get() = currentSslContext

    // TLS 하드닝 설정 접근자 (SmtpSession 등에서 사용)
    internal val tlsHandshakeTimeout: Long = tlsHandshakeTimeoutMs.toLong()

    init {
        scheduleCertificateReload()
        scheduleRateLimiterCleanup()
    }

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

    suspend fun start(
        coroutineContext: CoroutineContext = Dispatchers.IO,
        wait: Boolean = false
    ): Boolean {
        return serverMutex.withLock {
            if (channelFuture == null) {
                val bootstrap = ServerBootstrap()
                bootstrap.group(bossGroup, workerGroup)
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
                log.info { "Started smtp server, listening on port $port" }

                if (wait) {
                    serverScope.launch(coroutineContext) {
                        channelFuture?.channel()?.closeFuture()?.sync()
                    }
                }
                true
            } else false
        }
    }

    /**
     * Graceful shutdown with session draining
     * 1. Stop accepting new connections
     * 2. Close all active sessions gracefully
     * 3. Wait for sessions to close (with timeout)
     * 4. Shutdown event loops
     */
    suspend fun stop(gracefulTimeoutMs: Long = 30000): Boolean = serverMutex.withLock {
        if (channelFuture != null) {
            try {
                log.info { "Initiating graceful shutdown (port=$port)" }
                
                // 1. Stop accepting new connections
                channelFuture?.channel()?.close()?.sync()
                log.info { "Stopped accepting new connections" }
                
                // 2. Close all active sessions
                sessionTracker.closeAllSessions()
                
                // 3. Wait for sessions to close gracefully
                val allClosed = sessionTracker.awaitAllSessionsClosed(gracefulTimeoutMs)
                if (!allClosed) {
                    log.warn { "Graceful shutdown timeout. Forcing remaining sessions to close" }
                }
                
                // 4. Shutdown event loops
                serverScope.cancel()
                workerGroup.shutdownGracefully()
                bossGroup.shutdownGracefully()
                
                log.info { "SMTP server stopped gracefully (port=$port)" }
                true
            } finally {
                channelFuture = null
            }
        } else false
    }
}
