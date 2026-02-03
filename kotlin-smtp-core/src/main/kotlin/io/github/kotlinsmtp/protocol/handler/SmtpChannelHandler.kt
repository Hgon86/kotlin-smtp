package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.server.SmtpInboundFrame
import io.github.kotlinsmtp.server.ProxyProtocolSupport
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.haproxy.HAProxyMessage
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

private val log = KotlinLogging.logger {}

internal class SmtpChannelHandler(private val server: SmtpServer) : ChannelInboundHandlerAdapter() {
    private lateinit var session: SmtpSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clientIp: String? = null
    private var inboundFrames: Channel<SmtpInboundFrame>? = null
    private var inboundJob: Job? = null
    private var rateLimitAccepted: Boolean = false

    // PROXY protocol을 사용하는 경우, 원본 IP를 알기 전까지 세션을 시작하면 안 됩니다.
    private var proxyReady: Boolean = !server.proxyProtocolEnabled
    private var implicitTlsReady: Boolean = !server.implicitTls
    private var sessionStarted: Boolean = false
    private var pendingRejectMessage: String? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        // PROXY 미사용인 경우: 즉시(프록시 IP가 아니라) 원본 TCP remoteAddress로 레이트리밋을 적용합니다.
        if (!server.proxyProtocolEnabled) {
            clientIp = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress

            if (clientIp != null && !server.rateLimiter.allowConnection(clientIp!!)) {
                scope.launch {
                    ctx.writeAndFlush("421 4.7.0 Too many connections from your IP address. Try again later.\r\n")
                    log.warn { "Rate limit: Rejected connection from $clientIp" }
                    ctx.close()
                }
                return
            }
            // allowConnection()이 true를 반환한 경우에만 releaseConnection()을 호출해야 합니다.
            // (allowConnection() 내부에서 거부 시 이미 롤백 decrement를 수행합니다)
            if (clientIp != null) rateLimitAccepted = true
        }

        // SMTPS(implicit TLS)인 경우: 핸드셰이크 완료 후에만 greeting(220)을 보내야 안전합니다.
        if (server.implicitTls) {
            val ssl = ctx.pipeline().get("ssl") as? SslHandler
            if (ssl == null) {
                ctx.close()
                return
            }
            ssl.handshakeFuture().addListener { f ->
                if (!f.isSuccess) {
                    log.warn(f.cause()) { "Implicit TLS handshake failed; closing connection" }
                    ctx.close()
                    return@addListener
                }
                implicitTlsReady = true
                scope.launch { maybeStartSession(ctx) }
            }
        } else {
            implicitTlsReady = true
        }

        // PROXY를 쓰지 않으면 여기서 바로 시작 가능
        scope.launch { maybeStartSession(ctx) }
    }

    private suspend fun maybeStartSession(ctx: ChannelHandlerContext) {
        if (sessionStarted) return
        if (!proxyReady) return
        if (!implicitTlsReady) return

        // PROXY가 활성화된 경우, 여기 시점에는 clientIp가 "원본 클라이언트 IP"로 확정되어 있어야 합니다.
        pendingRejectMessage?.let { msg ->
            // implicit TLS에서는 최소한 핸드셰이크 이후에만 응답을 보낼 수 있으므로, 여기서 처리합니다.
            ctx.writeAndFlush("$msg\r\n")
            ctx.close()
            return
        }

        sessionStarted = true
        startSession(ctx, implicitTlsReady = server.implicitTls)
    }

    private suspend fun startSession(ctx: ChannelHandlerContext, implicitTlsReady: Boolean) {
        session = SmtpSession(ctx.channel(), server)
        if (implicitTlsReady) {
            session.markImplicitTlsActive()
        }

        // 중요: SMTP는 입력 순서가 프로토콜 의미 그 자체입니다.
        // msg마다 launch하면 순서가 뒤섞일 수 있으므로, 세션당 단일 소비 코루틴(actor)로 순차 처리합니다.
        inboundFrames = Channel<SmtpInboundFrame>(capacity = 1024).also { ch: Channel<SmtpInboundFrame> ->
            inboundJob = scope.launch {
                for (frame in ch) {
                    session.handleIncomingFrame(frame)
                }
            }
        }

        session.handle()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // PROXY protocol(v1): 접속 직후 HAProxyMessageDecoder가 1회만 HAProxyMessage를 내보냅니다.
        if (msg is HAProxyMessage) {
            // Netty handler 메서드는 suspend가 아니므로 코루틴으로 넘겨 처리합니다.
            // HAProxyMessage는 ReferenceCounted이므로 retain/release를 정확히 맞춥니다.
            ReferenceCountUtil.retain(msg)
            scope.launch {
                try {
                    handleProxyMessage(ctx, msg)
                } finally {
                    ReferenceCountUtil.release(msg)
                }
            }

            ReferenceCountUtil.release(msg)
            return
        }

        if (msg is SmtpInboundFrame) {  // 커스텀 디코더가 프레임을 출력합니다
            // 세션이 시작되기 전에는 SMTP 프레임을 처리할 수 없습니다(PROXY/implicit TLS gating).
            val ch = inboundFrames
            if (ch == null || !this::session.isInitialized) {
                ctx.close()
                return
            }

            val offered = ch.trySend(msg)
            if (offered.isFailure) {
                scope.launch {
                    session.sendResponse(
                        SmtpStatusCode.SERVICE_NOT_AVAILABLE.code,
                        "Input buffer overflow. Closing connection."
                    )
                    ctx.close()
                }
            }
        }
    }

    private suspend fun handleProxyMessage(ctx: ChannelHandlerContext, msg: HAProxyMessage) {
        val proxyRemote = ctx.channel().remoteAddress() as? InetSocketAddress
        val trusted = ProxyProtocolSupport.isTrustedProxy(proxyRemote, server.trustedProxyCidrsParsed)
        if (!trusted) {
            log.warn { "PROXY protocol header from untrusted proxy remote=${proxyRemote?.address?.hostAddress ?: proxyRemote}" }
            ctx.close()
            return
        }

        val sourceAddr = msg.sourceAddress()
        val sourcePort = msg.sourcePort()
        if (sourceAddr.isNullOrBlank() || sourcePort <= 0) {
            log.warn { "Invalid PROXY header: sourceAddr=$sourceAddr sourcePort=$sourcePort; closing" }
            ctx.close()
            return
        }

        // 원본 클라이언트 주소를 채널 속성으로 저장해 세션/로깅/레이트리밋이 일관되게 사용하도록 합니다.
        val realPeer = InetSocketAddress(sourceAddr, sourcePort)
        ctx.channel().attr(ProxyProtocolSupport.REAL_PEER).set(realPeer)
        clientIp = sourceAddr
        proxyReady = true

        // 디코더는 1회 처리 후 제거해 오버헤드 및 오동작 가능성을 줄입니다.
        runCatching { ctx.pipeline().remove("proxyDecoder") }

        // PROXY 사용 시에는 여기에서 레이트리밋을 적용해야 "원본 IP" 기준이 됩니다.
        if (clientIp != null && !server.rateLimiter.allowConnection(clientIp!!)) {
            val response = "421 4.7.0 Too many connections from your IP address. Try again later."
            pendingRejectMessage = response
            log.warn { "Rate limit (proxy): Rejected connection from $clientIp" }

            // 평문 리스너는 즉시 응답 가능, implicit TLS는 핸드셰이크 완료 후 maybeStartSession에서 응답
            if (!server.implicitTls) {
                ctx.writeAndFlush("$response\r\n")
                ctx.close()
            } else {
                // TLS가 아직 준비되지 않았을 수 있으므로, maybeStartSession에서 처리하도록 둡니다.
                maybeStartSession(ctx)
            }
            return
        }
        if (clientIp != null) rateLimitAccepted = true

        maybeStartSession(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // Rate Limiter 연결 해제
        // - allowConnection()이 실패한 연결은 이미 롤백되어 있으므로 release 하면 안 됩니다.
        if (rateLimitAccepted) {
            clientIp?.let { server.rateLimiter.releaseConnection(it) }
        }
        
        inboundFrames?.close()
        inboundJob?.cancel()
        scope.cancel()
        if (this::session.isInitialized) {
            session.close()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error(cause) { "Error in SMTP session" }
        inboundFrames?.close()
        inboundJob?.cancel()
        scope.cancel()
        if (this::session.isInitialized) {
            session.close()
        }
        ctx.close()
    }

    /// 타임아웃 이벤트 처리
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            scope.launch {
                if (this@SmtpChannelHandler::session.isInitialized) {
                    session.sendResponse(
                        421,
                        "4.4.2 Idle timeout. Closing connection."
                    )
                }
                log.info { "Connection idle timeout - closing channel" }
                ctx.close()
            }
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }
}
