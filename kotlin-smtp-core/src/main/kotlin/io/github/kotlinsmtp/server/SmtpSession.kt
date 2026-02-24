package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData
import io.github.kotlinsmtp.protocol.command.api.SmtpCommands
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import io.github.kotlinsmtp.spi.SmtpMessageEnvelope
import io.github.kotlinsmtp.spi.SmtpMessageRejectedEvent
import io.github.kotlinsmtp.spi.SmtpMessageStage
import io.github.kotlinsmtp.spi.SmtpMessageTransferMode
import io.github.kotlinsmtp.spi.SmtpSessionContext
import io.github.kotlinsmtp.spi.SmtpSessionEndedEvent
import io.github.kotlinsmtp.spi.SmtpSessionEndReason
import io.github.kotlinsmtp.spi.SmtpSessionStartedEvent
import io.github.kotlinsmtp.utils.Values
import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel as KChannel

private val log = KotlinLogging.logger {}

internal class SmtpSession(
    private val channel: Channel,
    val server: SmtpServer,
) {
    // Limit inbound line-channel capacity to prevent memory pressure during bursts
    private val incomingFrames = KChannel<SmtpInboundFrame>(1024)
    private val closing = AtomicBoolean(false)
    private val tlsUpgrading = AtomicBoolean(false)
    private val sessionActive = MutableStateFlow(true)
    internal val sessionId: String = UUID.randomUUID().toString().take(8)

    @Volatile
    internal var endReason: SmtpSessionEndReason = SmtpSessionEndReason.UNKNOWN

    internal val envelopeRecipients: MutableList<String> = mutableListOf()

    private val backpressure = SmtpBackpressureController(
        scope = server.serverScope,
        isTlsUpgrading = { tlsUpgrading.get() },
        setAutoRead = { enabled -> setAutoReadOnEventLoop(enabled) },
    )

    private val tlsUpgrade = SmtpTlsUpgradeManager(
        channel = channel,
        server = server,
        incomingFrames = incomingFrames,
        tlsUpgrading = tlsUpgrading,
        setAutoReadOnEventLoop = { enabled -> setAutoReadOnEventLoop(enabled) },
        onFrameDiscarded = { frame -> discardQueuedFrame(frame) },
    )
    private val logSanitizer = SmtpSessionLogSanitizer()
    private val responseFormatter = SmtpResponseFormatter()
    private val protocolHandlerHolder = SmtpProtocolHandlerHolder(server.transactionHandlerCreator)
    private val frameProcessor = SmtpSessionFrameProcessor(
        sessionId = sessionId,
        incomingFrames = incomingFrames,
        backpressure = backpressure,
        channel = channel,
        logSanitizer = logSanitizer,
        responseFormatter = responseFormatter,
        inDataMode = { inDataMode },
        getDataModeFramingHint = { dataModeFramingHint },
        setDataModeFramingHint = { value -> dataModeFramingHint = value },
        failAndClose = {
            shouldQuit = true
            close()
        },
        sendResponse = { code, message -> sendResponse(code, message) },
        sendResponseAwait = { code, message -> sendResponseAwait(code, message) },
        isClosing = { closing.get() },
        isTlsUpgrading = { tlsUpgrading.get() },
        closeOnly = { close() },
    )

    @Volatile
    var shouldQuit = false
    var sessionData = SessionData(); internal set
    var currentMessageSize = 0; internal set // Track current message size

    /**
     * Whether DATA is currently being received.
     * - Security/operations: used to avoid logging DATA body.
     * - When switched to false, also resets framing hint (dataModeFramingHint).
     */
    @Volatile
    var inDataMode: Boolean = false
        internal set(value) {
            field = value
            if (!value) {
                dataModeFramingHint = false
            }
            // Provide hint so Netty decoder does not auto-detect BDAT while in DATA mode.
            channel.attr(SmtpInboundDecoder.IN_DATA_MODE).set(value)
        }

    /**
     * Framing hint used when body may enter pipeline right after DATA line.
     *
     * - Body lines may arrive even before `inDataMode=true` (before 354 response).
     * - This hint is used only for "line length limit" and "sensitive log masking".
     */
    @Volatile
    private var dataModeFramingHint: Boolean = false

    val transactionHandler: SmtpProtocolHandler?
        get() {
            return protocolHandlerHolder.getOrCreate(sessionData)
        }

    /**
     * BDAT (CHUNKING) streaming state
     * - BDAT can be called multiple times, so stream/job are kept for one transaction.
     */
    internal val bdatState: BdatStreamingState = BdatStreamingState()

    var isTls: Boolean = false
        private set

    // Flag to enforce EHLO/HELO after STARTTLS
    var requireEhloAfterTls: Boolean = false

    suspend fun handle() {
        // Graceful shutdown: register in session tracker
        server.sessionTracker.register(sessionId, this)
        
        try {
            // Set session context
            sessionData.serverHostname = server.hostname
            sessionData.peerAddress = SmtpPeerAddressResolver.resolve(
                ProxyProtocolSupport.effectiveRemoteSocketAddress(channel) ?: channel.remoteAddress(),
            )
            sessionData.tlsActive = isTls

            log.info { "SMTP session started from ${sessionData.peerAddress}" }
            sendResponse(220, "${server.hostname} ${server.serviceName} Service ready")

            if (server.hasEventHooks()) {
                server.notifyHooks { hook ->
                    hook.onSessionStarted(
                        SmtpSessionStartedEvent(
                            context = buildSessionContext(),
                        )
                    )
                }
            }

            while (!shouldQuit && sessionActive.value) {
                val line = readLine()
                if (line != null) {
                    SmtpCommands.handle(line, this)
                } else {
                    break
                }
            }
            
            log.info { "SMTP session ended" }
        } finally {
            runCatching {
                if (server.hasEventHooks()) {
                    server.notifyHooks { hook ->
                        hook.onSessionEnded(
                            SmtpSessionEndedEvent(
                                context = buildSessionContext(),
                                reason = endReason,
                            )
                        )
                    }
                }
            }
            // Graceful shutdown: unregister from session tracker
            server.sessionTracker.unregister(sessionId)
            channel.close()
        }
    }

    internal fun buildSessionContext(): SmtpSessionContext = SmtpSessionContext(
        sessionId = sessionId,
        peerAddress = sessionData.peerAddress,
        serverHostname = sessionData.serverHostname,
        helo = sessionData.helo,
        tlsActive = sessionData.tlsActive,
        authenticated = sessionData.isAuthenticated,
    )

    internal fun buildMessageEnvelopeSnapshot(): SmtpMessageEnvelope = SmtpMessageEnvelope(
        mailFrom = sessionData.mailFrom ?: "",
        rcptTo = envelopeRecipients.toList(),
        dsnEnvid = sessionData.dsnEnvid,
        dsnRet = sessionData.dsnRet,
        rcptDsn = sessionData.rcptDsnView.toMap(),
    )

    /**
     * Emit per-message rejection hook event with a single normalized path.
     */
    internal suspend fun notifyMessageRejected(
        transferMode: SmtpMessageTransferMode,
        stage: SmtpMessageStage,
        responseCode: Int,
        responseMessage: String,
    ) {
        if (!server.hasEventHooks()) return
        val context = buildSessionContext()
        val envelope = buildMessageEnvelopeSnapshot()
        server.notifyHooks { hook ->
            hook.onMessageRejected(
                SmtpMessageRejectedEvent(
                    context = context,
                    envelope = envelope,
                    transferMode = transferMode,
                    stage = stage,
                    responseCode = responseCode,
                    responseMessage = responseMessage,
                )
            )
        }
    }

    internal suspend fun readLine(): String? {
        return frameProcessor.readLine()
    }

    internal suspend fun readBytesExact(expectedBytes: Int): ByteArray? {
        return frameProcessor.readBytesExact(expectedBytes)
    }

    internal suspend fun respondLine(message: String) {
        // Output encoding is handled by Netty pipeline (StringEncoder).
        channel.writeAndFlush("$message\r\n")
        log.info { "Session[$sessionId] <- $message" }
    }

    /**
     * Used when pipeline must be changed only after plaintext flush is fully completed, such as right after STARTTLS.
     */
    internal suspend fun respondLineAwait(message: String) {
        channel.writeAndFlush("$message\r\n").awaitCompletion()
        log.info { "Session[$sessionId] <- $message" }
    }

    suspend fun sendResponse(code: Int, message: String? = null) {
        respondLine(responseFormatter.formatLine(code, message))
    }

    suspend fun sendResponseAwait(code: Int, message: String? = null) {
        respondLineAwait(responseFormatter.formatLine(code, message))
    }

    suspend fun sendMultilineResponse(code: Int, lines: List<String>) {
        responseFormatter.formatMultiline(code, lines).forEach { line ->
            respondLine(line)
        }
    }

    /**
     * Reset transaction (single-message) scoped state.
     *
     * - preserveGreeting=true: keep HELO/EHLO state
     * - preserveAuth=true: keep AUTH state (RFC 4954: RSET etc. do not clear auth state)
     *
     * NOTE: after STARTTLS, auth/session state must be reset per RFC 3207, so call with preserveAuth=false.
     */
    suspend fun resetTransaction(preserveGreeting: Boolean = true, preserveAuth: Boolean = preserveGreeting) {
        // Clean up active BDAT stream if present (safe for RSET/transaction end).
        clearBdatState()
        clearDataModeFramingHints()

        envelopeRecipients.clear()
        protocolHandlerHolder.doneAndClear()
        sessionData = SmtpSessionDataResetter.reset(
            current = sessionData,
            preserveGreeting = preserveGreeting,
            preserveAuth = preserveAuth,
            serverHostname = server.hostname,
            peerAddress = SmtpPeerAddressResolver.resolve(
                ProxyProtocolSupport.effectiveRemoteSocketAddress(channel) ?: channel.remoteAddress(),
            ),
            tlsActive = isTls,
        )
        currentMessageSize = 0  // Reset message size
    }

    /** Clean up BDAT streaming state (on RSET/session end). */
    internal suspend fun clearBdatState() {
        bdatState.clear()
    }

    internal fun isBdatInProgress(): Boolean = bdatState.isActive

    /**
     * Clears DATA-mode framing hints used by session/decoder state.
     */
    internal fun clearDataModeFramingHints() {
        dataModeFramingHint = false
        channel.attr(SmtpInboundDecoder.DATA_FRAMING_HINT).set(false)
        channel.attr(SmtpInboundDecoder.IN_DATA_MODE).set(false)
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        sessionActive.value = false
        // Clean up active streams/jobs such as BDAT to prevent leaks.
        // close() is not suspend, so cleanup is done asynchronously in server scope.
        server.serverScope.launch {
            runCatching { clearBdatState() }
            runCatching { protocolHandlerHolder.doneAndClear() }
        }
        channel.close()
    }

    /**
     * Prevent further plaintext reads during STARTTLS upgrade, and prepare to treat queued frames
     * as protocol violation (pipelining) if already present.
     *
     * @return true if upgrade can continue
     */
    internal suspend fun beginStartTlsUpgrade(): Boolean = tlsUpgrade.begin()

    /**
     * Must be called after 220 response is flushed in plaintext.
     * - Performs SslHandler insertion -> autoRead resume -> wait handshake completion -> session state reset/flag update
     *   in the same coroutine flow.
     */
    internal suspend fun finishStartTlsUpgrade() {
        try {
            tlsUpgrade.complete(
                onHandshakeSuccess = {
                    isTls = true
                    sessionData.tlsActive = true
                    // After STARTTLS, session must be reset including authentication state.
                    resetTransaction(preserveGreeting = false, preserveAuth = false)
                    requireEhloAfterTls = true
                },
            )
        } catch (t: Throwable) {
            log.warn(t) { "TLS handshake failed; closing connection" }
            close()
        }
    }

    private suspend fun setAutoReadOnEventLoop(enabled: Boolean) {
        suspendCancellableCoroutine<Unit> { cont ->
            channel.eventLoop().execute {
                channel.config().isAutoRead = enabled
                if (enabled) channel.read()
                cont.resume(Unit)
            }
        }
    }

    private fun discardQueuedFrame(frame: SmtpInboundFrame) {
        frameProcessor.discardQueuedFrame(frame)
    }

    /**
     * Used in channelRead path called from Netty event loop.
     * - Applies memory limits including bytes in-flight cap before enqueueing.
     */
    internal fun tryEnqueueInboundFrame(frame: SmtpInboundFrame): Boolean {
        return frameProcessor.tryEnqueueInboundFrame(frame)
    }

    /** Returns original client IP for rate limiter use. */
    internal fun clientIpAddress(): String? {
        val address = ProxyProtocolSupport.effectiveRemoteSocketAddress(channel) ?: channel.remoteAddress()
        val inet = address as? InetSocketAddress ?: return null
        return inet.address?.hostAddress ?: inet.hostString
    }

    internal fun markImplicitTlsActive() {
        // Called after handshake completion on SMTPS (implicit TLS).
        isTls = true
        sessionData.tlsActive = true
    }
}

/**
 * Minimal await helper to wait for Netty ChannelFuture in coroutines
 */
private suspend fun ChannelFuture.awaitCompletion(): Unit =
    suspendCancellableCoroutine { cont ->
        this.addListener { f ->
            if (f.isSuccess) cont.resume(Unit) else cont.resumeWithException(f.cause())
        }
        cont.invokeOnCancellation { runCatching { this.cancel(false) } }
    }
