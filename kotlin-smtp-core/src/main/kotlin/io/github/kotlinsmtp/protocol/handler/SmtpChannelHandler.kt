package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.server.SmtpInboundFrame
import io.github.kotlinsmtp.server.ProxyProtocolSupport
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.spi.SmtpSessionEndReason
import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.haproxy.HAProxyMessage
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

internal class SmtpChannelHandler(private val server: SmtpServer) : ChannelInboundHandlerAdapter() {
    private lateinit var session: SmtpSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clientIp: String? = null
    private val sessionDeferred = CompletableDeferred<SmtpSession>()
    private var rateLimitAccepted: Boolean = false

    // 세션 시작 전(Proxy/implicit TLS 게이팅 포함) 유입된 라인 프레임을 소량 버퍼링합니다.
    // - SMTP 클라이언트가 greeting(220) 전에 커맨드를 보내는 경우를 허용하기 위한 호환성 목적
    // - bytes 프레임은 절대 버퍼링하지 않습니다(BDAT 등은 동기화가 깨질 수 있음)
    private val pendingLines: ArrayDeque<SmtpInboundFrame.Line> = ArrayDeque()

    private val startGate: SessionStartGate = SessionStartGate(server)

    override fun channelActive(ctx: ChannelHandlerContext) {
        // PROXY 미사용인 경우: 즉시(프록시 IP가 아니라) 원본 TCP remoteAddress로 레이트리밋을 적용합니다.
        if (!server.proxyProtocolEnabled) {
            clientIp = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress

            if (clientIp != null && !server.rateLimiter.allowConnection(clientIp!!)) {
                ctx.writeAndFlush("421 4.7.0 Too many connections from your IP address. Try again later.\r\n")
                    .addListener(ChannelFutureListener.CLOSE)
                log.warn { "Rate limit: Rejected connection from $clientIp" }
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
                startGate.markSatisfied(SessionStartGate.Condition.IMPLICIT_TLS)
                scope.launch { maybeStartSession(ctx) }
            }
        } else {
            startGate.markSatisfied(SessionStartGate.Condition.IMPLICIT_TLS)
        }

        // PROXY를 쓰지 않으면 여기서 바로 시작 가능
        scope.launch { maybeStartSession(ctx) }
    }

    private suspend fun maybeStartSession(ctx: ChannelHandlerContext) {
        if (!startGate.tryStartIfReady()) return

        startGate.pendingRejectMessage?.let { msg ->
            // implicit TLS에서는 최소한 핸드셰이크 이후에만 응답을 보낼 수 있으므로, 여기서 처리합니다.
            ctx.writeAndFlush("$msg\r\n").addListener(ChannelFutureListener.CLOSE)
            return
        }

        startSession(ctx, implicitTlsReady = server.implicitTls)
    }

    private suspend fun startSession(ctx: ChannelHandlerContext, implicitTlsReady: Boolean) {
        session = SmtpSession(ctx.channel(), server)
        if (!sessionDeferred.isCompleted) sessionDeferred.complete(session)

        // 세션 시작 전 버퍼된 라인을 세션 입력 큐로 이관합니다(순서 보장).
        // - 큐가 넘치면 세션이 자체적으로 421 후 종료합니다.
        while (pendingLines.isNotEmpty()) {
            val line = pendingLines.removeFirst()
            session.tryEnqueueInboundFrame(line)
        }

        if (implicitTlsReady) {
            session.markImplicitTlsActive()
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
            if (!sessionDeferred.isCompleted) {
                // Session is not started yet; never buffer raw bytes pre-greeting.
                if (msg is SmtpInboundFrame.Bytes) {
                    ctx.close()
                    return
                }

                if (pendingLines.size >= 16) {
                    ctx.writeAndFlush("421 4.3.0 Input buffer overflow. Closing connection.\r\n")
                        .addListener(ChannelFutureListener.CLOSE)
                    return
                }
                pendingLines.addLast(msg as SmtpInboundFrame.Line)
                return
            }

            // 세션 시작 이후: 단일 큐로 직접 enqueue(이중 버퍼 제거)
            if (this@SmtpChannelHandler::session.isInitialized) {
                session.tryEnqueueInboundFrame(msg)
            } else {
                ctx.close()
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
        startGate.markSatisfied(SessionStartGate.Condition.PROXY)

        // 디코더는 1회 처리 후 제거해 오버헤드 및 오동작 가능성을 줄입니다.
        runCatching { ctx.pipeline().remove("proxyDecoder") }

        // PROXY 사용 시에는 여기에서 레이트리밋을 적용해야 "원본 IP" 기준이 됩니다.
        if (clientIp != null && !server.rateLimiter.allowConnection(clientIp!!)) {
            val response = "421 4.7.0 Too many connections from your IP address. Try again later."
            startGate.pendingRejectMessage = response
            log.warn { "Rate limit (proxy): Rejected connection from $clientIp" }

            // 평문 리스너는 즉시 응답 가능, implicit TLS는 핸드셰이크 완료 후 maybeStartSession에서 응답
            if (!server.implicitTls) {
                ctx.writeAndFlush("$response\r\n").addListener(ChannelFutureListener.CLOSE)
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
        
        scope.cancel()
        if (this::session.isInitialized) {
            session.close()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Known inbound framing errors should be mapped to a deterministic SMTP response when possible.
        if (cause is TooLongFrameException || cause is IllegalArgumentException) {
            val looksLikeBdat = (cause.message?.contains("BDAT", ignoreCase = true) == true)
            val status = if (looksLikeBdat) {
                SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION
            } else {
                SmtpStatusCode.COMMAND_SYNTAX_ERROR
            }

            // Avoid echoing exception details back to clients.
            val safeMessage = when {
                looksLikeBdat -> "BDAT chunk too large"
                cause is TooLongFrameException -> "Line too long"
                else -> "Protocol error"
            }

            log.warn(cause) { "Inbound protocol error; closing connection" }

            scope.launch {
                if (this@SmtpChannelHandler::session.isInitialized) {
                    session.endReason = SmtpSessionEndReason.PROTOCOL_ERROR
                    runCatching { session.sendResponseAwait(status.code, safeMessage) }
                    ctx.close()
                } else {
                    ctx.writeAndFlush("${status.code} ${status.enhancedCode} $safeMessage\r\n")
                        .addListener(ChannelFutureListener.CLOSE)
                }
            }
            return
        }

        log.error(cause) { "Error in SMTP session" }
        scope.cancel()
        if (this::session.isInitialized) {
            session.endReason = SmtpSessionEndReason.PROTOCOL_ERROR
            session.close()
        }
        // Flush any pending writes without sending extra bytes.
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
    }

    /// 타임아웃 이벤트 처리
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            scope.launch {
                if (this@SmtpChannelHandler::session.isInitialized) {
                    session.endReason = SmtpSessionEndReason.IDLE_TIMEOUT
                    session.sendResponseAwait(
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

private class SessionStartGate(server: SmtpServer) {
    enum class Condition {
        PROXY,
        IMPLICIT_TLS,
    }

    private val pending: EnumSet<Condition> = EnumSet.noneOf(Condition::class.java).also {
        if (server.proxyProtocolEnabled) it.add(Condition.PROXY)
        if (server.implicitTls) it.add(Condition.IMPLICIT_TLS)
    }

    private val started = AtomicBoolean(false)

    @Volatile
    var pendingRejectMessage: String? = null

    fun markSatisfied(condition: Condition) {
        pending.remove(condition)
    }

    fun tryStartIfReady(): Boolean {
        if (pending.isNotEmpty()) return false
        return started.compareAndSet(false, true)
    }
}
