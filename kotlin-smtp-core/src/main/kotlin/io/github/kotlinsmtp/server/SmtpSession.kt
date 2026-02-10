package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData
import io.github.kotlinsmtp.protocol.command.api.SmtpCommands
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import io.github.kotlinsmtp.spi.SmtpMessageEnvelope
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
    // 입력 라인 채널 용량 제한으로 폭주 시 메모리 압박 방지
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
    var currentMessageSize = 0; internal set // 현재 메시지 크기 추적

    /**
     * DATA 수신 중 여부
     * - 보안/운영: DATA 본문은 로그에 남기지 않기 위해 사용합니다.
     * - false로 전환 시 프레이밍 힌트(dataModeFramingHint)도 함께 리셋합니다.
     */
    @Volatile
    var inDataMode: Boolean = false
        internal set(value) {
            field = value
            if (!value) {
                dataModeFramingHint = false
            }
            // Netty 디코더가 DATA 모드에서는 BDAT auto-detect를 하지 않도록 힌트를 줍니다.
            channel.attr(SmtpInboundDecoder.IN_DATA_MODE).set(value)
        }

    /**
     * DATA 라인 이후 본문이 파이프라인으로 들어오는 경우를 위해 사용하는 프레이밍 힌트입니다.
     *
     * - `inDataMode=true`가 되기 전에도(354 응답 전) 본문 라인이 유입될 수 있습니다.
     * - 이 힌트는 "라인 길이 제한"과 "민감 로그 마스킹"에만 사용됩니다.
     */
    @Volatile
    private var dataModeFramingHint: Boolean = false

    val transactionHandler: SmtpProtocolHandler?
        get() {
            return protocolHandlerHolder.getOrCreate(sessionData)
        }

    /**
     * BDAT(CHUNKING) 스트리밍 상태
     * - BDAT는 여러 번 호출될 수 있으므로, 하나의 트랜잭션 동안 스트림/잡을 유지합니다.
     */
    internal val bdatState: BdatStreamingState = BdatStreamingState()

    var isTls: Boolean = false
        private set

    // STARTTLS 이후 EHLO/HELO를 강제하기 위한 플래그
    var requireEhloAfterTls: Boolean = false

    suspend fun handle() {
        // Graceful shutdown: 세션 추적에 등록
        server.sessionTracker.register(sessionId, this)
        
        try {
            // 세션 컨텍스트 설정
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
            // Graceful shutdown: 세션 추적에서 제거
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

    internal suspend fun readLine(): String? {
        return frameProcessor.readLine()
    }

    internal suspend fun readBytesExact(expectedBytes: Int): ByteArray? {
        return frameProcessor.readBytesExact(expectedBytes)
    }

    internal suspend fun respondLine(message: String) {
        // 출력 인코딩은 Netty 파이프라인(StringEncoder)에 맡깁니다.
        channel.writeAndFlush("$message\r\n")
        log.info { "Session[$sessionId] <- $message" }
    }

    /**
     * STARTTLS 직후처럼 "반드시 평문으로 flush 완료 후" 파이프라인을 바꿔야 하는 경우에 사용합니다.
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
     * 트랜잭션(메일 한 통) 단위 상태를 리셋합니다.
     *
     * - preserveGreeting=true: HELO/EHLO 상태 유지
     * - preserveAuth=true: AUTH 상태 유지 (RFC 4954: RSET 등은 인증 상태를 지우지 않음)
     *
     * NOTE: STARTTLS 후에는 RFC 3207에 따라 인증/세션 상태를 리셋해야 하므로 preserveAuth=false로 호출해야 합니다.
     */
    suspend fun resetTransaction(preserveGreeting: Boolean = true, preserveAuth: Boolean = preserveGreeting) {
        // BDAT 진행 중이던 스트림이 있으면 정리합니다(RSET/트랜잭션 종료 시 안전).
        clearBdatState()

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
        currentMessageSize = 0  // 메시지 크기 리셋
    }

    /** BDAT 스트리밍 상태를 정리합니다(RSET/세션 종료 시). */
    internal suspend fun clearBdatState() {
        bdatState.clear()
    }

    internal fun isBdatInProgress(): Boolean = bdatState.isActive

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        sessionActive.value = false
        // BDAT 등 진행 중인 스트림/잡이 있으면 누수 방지를 위해 정리합니다.
        // close()는 suspend가 아니므로 서버 스코프에서 비동기 정리합니다.
        server.serverScope.launch {
            runCatching { clearBdatState() }
            runCatching { protocolHandlerHolder.doneAndClear() }
        }
        channel.close()
    }

    /**
     * STARTTLS 업그레이드 구간에서 입력(평문)을 더 읽지 않도록 막고, 이미 큐에 들어온 프레임이 있으면
     * 프로토콜 위반(파이프라이닝)으로 간주할 수 있도록 준비합니다.
     *
     * @return 업그레이드를 계속 진행해도 되면 true
     */
    internal suspend fun beginStartTlsUpgrade(): Boolean = tlsUpgrade.begin()

    /**
     * 220 응답이 평문으로 flush된 뒤에 호출되어야 합니다.
     * - SslHandler 삽입 → autoRead 재개 → 핸드셰이크 완료 대기 → 세션 상태 리셋/플래그 적용을
     *   동일한 코루틴 흐름에서 수행합니다.
     */
    internal suspend fun finishStartTlsUpgrade() {
        try {
            tlsUpgrade.complete(
                onHandshakeSuccess = {
                    isTls = true
                    sessionData.tlsActive = true
                    // STARTTLS 이후에는 인증 상태를 포함해 세션을 리셋해야 합니다.
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
     * Netty 이벤트 루프에서 호출되는 channelRead 경로에서 사용합니다.
     * - 여기서 bytes inflight cap을 포함해 "큐에 넣기 전"에 메모리 상한을 적용합니다.
     */
    internal fun tryEnqueueInboundFrame(frame: SmtpInboundFrame): Boolean {
        return frameProcessor.tryEnqueueInboundFrame(frame)
    }

    /** 레이트리미터에서 사용할 원본 클라이언트 IP를 반환합니다. */
    internal fun clientIpAddress(): String? {
        val address = ProxyProtocolSupport.effectiveRemoteSocketAddress(channel) ?: channel.remoteAddress()
        val inet = address as? InetSocketAddress ?: return null
        return inet.address?.hostAddress ?: inet.hostString
    }

    internal fun markImplicitTlsActive() {
        // SMTPS(implicit TLS)에서 핸드셰이크 완료 이후 호출됩니다.
        isTls = true
        sessionData.tlsActive = true
    }
}

/**
 * Netty ChannelFuture를 코루틴에서 기다리기 위한 최소 await 헬퍼
 */
private suspend fun ChannelFuture.awaitCompletion(): Unit =
    suspendCancellableCoroutine { cont ->
        this.addListener { f ->
            if (f.isSuccess) cont.resume(Unit) else cont.resumeWithException(f.cause())
        }
        cont.invokeOnCancellation { runCatching { this.cancel(false) } }
    }
