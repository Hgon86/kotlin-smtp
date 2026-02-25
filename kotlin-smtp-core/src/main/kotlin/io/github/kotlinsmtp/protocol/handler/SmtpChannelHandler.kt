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
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol
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

    // Buffer a small number of inbound line frames before session start (including proxy/implicit TLS gating).
    // - Compatibility purpose to tolerate SMTP clients sending commands before greeting (220)
    // - Never buffer bytes frames (BDAT etc. may break synchronization)
    private val pendingLines: ArrayDeque<SmtpInboundFrame.Line> = ArrayDeque()

    private val startGate: SessionStartGate = SessionStartGate(server)

    override fun channelActive(ctx: ChannelHandlerContext) {
        // When PROXY is not used: apply rate limit immediately using original TCP remoteAddress (not proxy IP).
        if (!server.proxyProtocolEnabled) {
            clientIp = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress

            if (clientIp != null && !server.rateLimiter.allowConnection(clientIp!!)) {
                ctx.writeAndFlush("421 4.7.0 Too many connections from your IP address. Try again later.\r\n")
                    .addListener(ChannelFutureListener.CLOSE)
                log.warn { "Rate limit: Rejected connection from $clientIp" }
                return
            }
            // Call releaseConnection() only when allowConnection() returned true.
            // (On rejection, allowConnection() already performs rollback decrement internally.)
            if (clientIp != null) rateLimitAccepted = true
        }

        // For SMTPS (implicit TLS): greeting (220) should be sent only after handshake completes.
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

        // If PROXY is not used, session can start immediately here
        scope.launch { maybeStartSession(ctx) }
    }

    private suspend fun maybeStartSession(ctx: ChannelHandlerContext) {
        if (!startGate.tryStartIfReady()) return

        startGate.pendingRejectMessage?.let { msg ->
            // With implicit TLS, responses can be sent safely only after handshake; handle it here.
            ctx.writeAndFlush("$msg\r\n").addListener(ChannelFutureListener.CLOSE)
            return
        }

        startSession(ctx, implicitTlsReady = server.implicitTls)
    }

    private suspend fun startSession(ctx: ChannelHandlerContext, implicitTlsReady: Boolean) {
        session = SmtpSession(ctx.channel(), server)
        if (!sessionDeferred.isCompleted) sessionDeferred.complete(session)

        // Move pre-session buffered lines into session input queue (preserving order).
        // - If queue overflows, session self-terminates after 421.
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
        // PROXY protocol (v1): HAProxyMessageDecoder emits HAProxyMessage only once right after connect.
        if (msg is HAProxyMessage) {
            // Netty handler methods are not suspend, so hand off processing to coroutine.
            // HAProxyMessage is ReferenceCounted, so retain/release must be balanced exactly.
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

        if (msg is SmtpInboundFrame) {  // Emitted by custom decoder
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

            // After session start: enqueue directly into single queue (remove double buffering)
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

        /**
         * Accept only TCP4/TCP6 to preserve concrete client address semantics for audit and rate limit.
         */
        val proxiedProtocol = msg.proxiedProtocol()
        if (proxiedProtocol != HAProxyProxiedProtocol.TCP4 && proxiedProtocol != HAProxyProxiedProtocol.TCP6) {
            log.warn { "Unsupported PROXY protocol family: $proxiedProtocol; closing" }
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

        // Store original client address in channel attribute for consistent session/logging/rate-limit usage.
        val realPeer = InetSocketAddress(sourceAddr, sourcePort)
        ctx.channel().attr(ProxyProtocolSupport.REAL_PEER).set(realPeer)
        clientIp = sourceAddr
        startGate.markSatisfied(SessionStartGate.Condition.PROXY)

        // Remove decoder after one-time processing to reduce overhead and malfunction risk.
        runCatching { ctx.pipeline().remove("proxyDecoder") }

        // With PROXY enabled, rate limit must be applied here to use "original IP" as reference.
        if (clientIp != null && !server.rateLimiter.allowConnection(clientIp!!)) {
            val response = "421 4.7.0 Too many connections from your IP address. Try again later."
            startGate.pendingRejectMessage = response
            log.warn { "Rate limit (proxy): Rejected connection from $clientIp" }

            // Plain listener can respond immediately; implicit TLS responds in maybeStartSession after handshake
            if (!server.implicitTls) {
                ctx.writeAndFlush("$response\r\n").addListener(ChannelFutureListener.CLOSE)
            } else {
                // TLS may not be ready yet, so defer handling to maybeStartSession.
                maybeStartSession(ctx)
            }
            return
        }
        if (clientIp != null) rateLimitAccepted = true

        maybeStartSession(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // Release rate-limiter connection slot
        // - Connections rejected by allowConnection() are already rolled back, so do not release again.
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

    /** Handles idle-timeout and other user-triggered events. */
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
