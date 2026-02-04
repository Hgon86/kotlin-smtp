package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData
import io.github.kotlinsmtp.protocol.command.api.SmtpCommands
import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import io.github.kotlinsmtp.utils.Values
import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.atomic.AtomicLong
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
    private val inflightBdatBytes = AtomicLong(0)
    private val closing = AtomicBoolean(false)
    private val sessionActive = MutableStateFlow(true)
    private val sessionId = UUID.randomUUID().toString().take(8)

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

    var transactionHandler: SmtpProtocolHandler? = null
        get() {
            if (field == null && server.transactionHandlerCreator != null) {
                val handler = server.transactionHandlerCreator.invoke()
                handler.init(sessionData)
                field = handler
            }
            return field
        }

    /**
     * BDAT(CHUNKING) 스트리밍 상태
     * - BDAT는 여러 번 호출될 수 있으므로, 하나의 트랜잭션 동안 스트림/잡을 유지합니다.
     */
    internal var bdatDataChannel: kotlinx.coroutines.channels.Channel<ByteArray>? = null
    internal var bdatStream: CoroutineInputStream? = null
    internal var bdatHandlerJob: Job? = null
    internal var bdatHandlerResult: CompletableDeferred<Result<Unit>>? = null

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
            sessionData.peerAddress = resolvePeer(ProxyProtocolSupport.effectiveRemoteSocketAddress(channel) ?: channel.remoteAddress())
            sessionData.tlsActive = isTls

            log.info { "SMTP session started from ${sessionData.peerAddress}" }
            sendResponse(220, "${server.hostname} ${server.serviceName} Service ready")

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
            // Graceful shutdown: 세션 추적에서 제거
            server.sessionTracker.unregister(sessionId)
            channel.close()
        }
    }

    private fun resolvePeer(address: Any?): String? {
        val inet = (address as? InetSocketAddress) ?: return address?.toString()
        // 운영 안정성: 역방향 DNS(hostName) 조회는 지연/블로킹의 원인이 될 수 있어 수행하지 않습니다.
        val ipOrHost = inet.address?.hostAddress ?: inet.hostString
        // IPv6는 ':'를 포함하므로 표준 형식([addr]:port)으로 표현합니다.
        return if (ipOrHost.contains(':')) "[$ipOrHost]:${inet.port}" else "$ipOrHost:${inet.port}"
    }

    internal suspend fun readLine(): String? {
        val frame = incomingFrames.receiveCatching().getOrNull() ?: return null
        return when (frame) {
            is SmtpInboundFrame.Line -> frame.text
            is SmtpInboundFrame.Bytes -> {
                inflightBdatBytes.addAndGet(-frame.bytes.size.toLong())
                // 프로토콜 동기화가 깨진 상태: 커맨드/데이터 라인을 기대했는데 raw bytes가 들어옴
                sendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Protocol sync error")
                shouldQuit = true
                close()
                null
            }
        }
    }

    internal suspend fun readBytesExact(expectedBytes: Int): ByteArray? {
        val frame = incomingFrames.receiveCatching().getOrNull() ?: return null
        return when (frame) {
            is SmtpInboundFrame.Bytes -> frame.bytes.also {
                inflightBdatBytes.addAndGet(-it.size.toLong())
                if (it.size != expectedBytes) {
                    // 디코더와 호출부 기대치가 어긋난 경우: 보수적으로 연결 종료
                    sendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Protocol sync error")
                    shouldQuit = true
                    close()
                }
            }
            is SmtpInboundFrame.Line -> {
                sendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Protocol sync error")
                shouldQuit = true
                close()
                null
            }
        }
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
        channel.writeAndFlush("$message\r\n").await()
        log.info { "Session[$sessionId] <- $message" }
    }

    suspend fun sendResponse(code: Int, message: String? = null) {
        respondLine(formatResponseLine(code, message))
    }

    suspend fun sendResponseAwait(code: Int, message: String? = null) {
        respondLineAwait(formatResponseLine(code, message))
    }

    suspend fun sendMultilineResponse(code: Int, lines: List<String>) {
        val statusCode = SmtpStatusCode.fromCode(code)
        val enhancedPrefix = statusCode?.enhancedCode?.let { "$it " } ?: ""

        lines.forEachIndexed { index, line ->
            if (index != lines.lastIndex)
                respondLine("$code-$enhancedPrefix$line")
            else
                respondLine("$code $enhancedPrefix$line")
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

        transactionHandler?.done()
        transactionHandler = null
        val oldHello = if (preserveGreeting) sessionData.helo else null
        val greeted = if (preserveGreeting) sessionData.greeted else false
        val usedEhlo = if (preserveGreeting) sessionData.usedEhlo else false
        val authenticated = if (preserveAuth) sessionData.isAuthenticated else false
        val authFailedAttempts = if (preserveAuth) sessionData.authFailedAttempts else null
        val authLockedUntilEpochMs = if (preserveAuth) sessionData.authLockedUntilEpochMs else null
        sessionData = SessionData().also {
            it.helo = oldHello
            it.greeted = greeted
            it.usedEhlo = usedEhlo
            it.serverHostname = server.hostname
            it.peerAddress = resolvePeer(ProxyProtocolSupport.effectiveRemoteSocketAddress(channel) ?: channel.remoteAddress())
            it.tlsActive = isTls
            it.isAuthenticated = authenticated
            it.authFailedAttempts = authFailedAttempts
            it.authLockedUntilEpochMs = authLockedUntilEpochMs
            // ESMTP 파라미터 초기화 (보안 강화)
            it.mailParameters = emptyMap()
            it.declaredSize = null
            it.smtpUtf8 = false
            // RFC 3461(DSN) 관련 파라미터 초기화(트랜잭션 단위)
            it.dsnEnvid = null
            it.dsnRet = null
            it.rcptDsn = mutableMapOf()
        }
        currentMessageSize = 0  // 메시지 크기 리셋
    }

    /** BDAT 스트리밍 상태를 정리합니다(RSET/세션 종료 시). */
    internal suspend fun clearBdatState() {
        // BDAT 핸들러 잡 정리
        bdatHandlerJob?.cancel()
        runCatching { bdatHandlerJob?.join() }

        // 스트림/채널 정리
        runCatching { bdatStream?.close() }
        runCatching { bdatDataChannel?.close() }

        bdatDataChannel = null
        bdatStream = null
        bdatHandlerJob = null
        bdatHandlerResult = null
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        sessionActive.value = false
        // BDAT 등 진행 중인 스트림/잡이 있으면 누수 방지를 위해 정리합니다.
        // close()는 suspend가 아니므로 서버 스코프에서 비동기 정리합니다.
        server.serverScope.launch {
            runCatching { clearBdatState() }
            runCatching { transactionHandler?.done() }
        }
        channel.close()
    }

    suspend fun handleIncomingFrame(frame: SmtpInboundFrame) {
        when (frame) {
            is SmtpInboundFrame.Line -> handleIncomingLine(frame.text)
            is SmtpInboundFrame.Bytes -> handleIncomingBytes(frame.bytes)
        }
    }

    private suspend fun handleIncomingBytes(bytes: ByteArray) {
        // 보안: 본문/바이너리 청크는 로그에 남기지 않습니다.
        // 운영 필요 시에도 길이만 남기도록 최소화합니다.
        log.debug { "Session[$sessionId] -> <BYTES:${bytes.size} bytes>" }

        val size = bytes.size
        if (!tryReserveBdatBytes(size)) {
            sendResponseAwait(SmtpStatusCode.SERVICE_NOT_AVAILABLE.code, "Input buffer overflow. Closing connection.")
            shouldQuit = true
            close()
            return
        }

        val result = incomingFrames.trySend(SmtpInboundFrame.Bytes(bytes))
        if (result.isFailure) {
            inflightBdatBytes.addAndGet(-size.toLong())
            sendResponseAwait(SmtpStatusCode.SERVICE_NOT_AVAILABLE.code, "Input buffer overflow. Closing connection.")
            shouldQuit = true
            close()
        }
    }

    private suspend fun handleIncomingLine(line: String) {
        // 보안: AUTH PLAIN 등 크리덴셜이 포함될 수 있는 라인은 그대로 로깅하지 않습니다.
        log.info { "Session[$sessionId] -> ${sanitizeIncomingForLog(line)}" }

        // DATA 라인 이후 본문이 파이프라인으로 들어오는 경우를 위해 힌트를 세팅합니다.
        // (inDataMode=true 반영 이전의 라인 길이 제한/로그 마스킹 안정화 목적)
        val commandPart = line.trimStart()
        if (!inDataMode && !dataModeFramingHint && commandPart.equals("DATA", ignoreCase = true)) {
            dataModeFramingHint = true
        } else if (dataModeFramingHint && line == ".") {
            dataModeFramingHint = false
        }
        
        // 라인 길이 검증 (DoS 방지)
        // - 커맨드 라인은 MAX_COMMAND_LINE_LENGTH
        // - DATA 본문 라인은 별도 상한(MAX_SMTP_LINE_LENGTH)으로 완화(본문 라인이 커맨드 상한을 넘을 수 있음)
        val maxAllowed = if (inDataMode || dataModeFramingHint) Values.MAX_SMTP_LINE_LENGTH else Values.MAX_COMMAND_LINE_LENGTH
        if (line.length > maxAllowed) {
            sendResponseAwait(
                SmtpStatusCode.COMMAND_SYNTAX_ERROR.code,
                "Line too long (max $maxAllowed bytes)"
            )
            shouldQuit = true
            close()
            return
        }
        
        val result = incomingFrames.trySend(SmtpInboundFrame.Line(line))
        if (result.isFailure) {
            // 입력 버퍼가 가득 찬 경우 세션을 종료하여 자원 남용 방지
            sendResponseAwait(SmtpStatusCode.SERVICE_NOT_AVAILABLE.code, "Input buffer overflow. Closing connection.")
            shouldQuit = true
            close()
        }
    }

    /**
     * 민감 명령 로깅 마스킹 유틸
     * - AUTH PLAIN <base64> 같은 경우 base64 안에 비밀번호가 포함될 수 있어 반드시 마스킹합니다.
     */
    private fun sanitizeIncomingForLog(line: String): String {
        // DATA 본문은 개인정보/메일 내용이 포함되므로 라인을 그대로 남기지 않습니다.
        if (inDataMode || dataModeFramingHint) {
            return if (line == ".") "<DATA:END>" else "<DATA:${line.length} chars>"
        }

        val trimmed = line.trimStart()
        if (!trimmed.regionMatches(0, "AUTH", 0, 4, ignoreCase = true)) return line

        val parts = trimmed.split(Values.whitespaceRegex, limit = 3)
        return when {
            parts.size >= 2 && parts[1].equals("PLAIN", ignoreCase = true) -> "AUTH PLAIN ***"
            parts.size >= 2 -> "AUTH ${parts[1]} ***"
            else -> "AUTH ***"
        }
    }

    private fun tryReserveBdatBytes(bytes: Int): Boolean {
        if (bytes <= 0) return true
        while (true) {
            val current = inflightBdatBytes.get()
            val next = current + bytes
            if (next > Values.MAX_INFLIGHT_BDAT_BYTES) return false
            if (inflightBdatBytes.compareAndSet(current, next)) return true
        }
    }

    private fun formatResponseLine(code: Int, message: String?): String {
        // message가 이미 Enhanced Status Code(예: "5.7.1 ...")를 포함하면 중복/왜곡을 피하기 위해 그대로 사용합니다.
        if (message != null && enhancedStatusRegex.containsMatchIn(message.trimStart())) {
            return "$code $message"
        }

        val statusCode = SmtpStatusCode.fromCode(code)
        return when {
            statusCode != null -> statusCode.formatResponse(message)
            message != null -> "$code $message"
            else -> code.toString()
        }
    }

    suspend fun startTls() {
        val sslContext = server.sslContext ?: return

        // Netty pipeline 변경은 channel event loop에서 수행합니다.
        suspendCancellableCoroutine { cont ->
            channel.eventLoop().execute {
                val pipeline = channel.pipeline()

                // 이미 TLS가 활성인 경우(또는 ssl 핸들러가 이미 있는 경우) 중복 추가 방지
                if (pipeline.get("ssl") != null) {
                    cont.resume(Unit)
                    return@execute
                }

                // STARTTLS 업그레이드: SslHandler를 파이프라인 맨 앞에 추가하면
                // 이후 inbound/outbound 데이터가 자동으로 복호화/암호화되어 기존 디코더를 건드릴 필요가 없습니다.
                val sslHandler = sslContext.newHandler(channel.alloc()).also {
                    // 서버 모드로 강제(안전망)
                    it.engine().useClientMode = false
                    // 핸드셰이크 타임아웃 설정
                    it.setHandshakeTimeout(server.tlsHandshakeTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                }

                pipeline.addFirst("ssl", sslHandler)

                // 핸드셰이크 결과에 따라 세션 상태를 전환합니다.
                sslHandler.handshakeFuture().addListener { future ->
                    if (future.isSuccess) {
                        // 핸드셰이크 성공 이후에만 TLS 플래그/세션 리셋/EHLO 강제를 적용합니다.
                        server.serverScope.launch {
                            isTls = true
                            sessionData.tlsActive = true
                            // STARTTLS 이후에는 인증 상태를 포함해 세션을 리셋해야 합니다.
                            resetTransaction(preserveGreeting = false, preserveAuth = false)
                            requireEhloAfterTls = true
                        }
                    } else {
                        log.warn(future.cause()) { "TLS handshake failed; closing connection" }
                        close()
                    }
                }

                cont.resume(Unit)
            }
        }
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
private suspend fun ChannelFuture.await(): Unit =
    suspendCancellableCoroutine { cont ->
        this.addListener { f ->
            if (f.isSuccess) cont.resume(Unit) else cont.resumeWithException(f.cause())
        }
        cont.invokeOnCancellation { runCatching { this.cancel(false) } }
    }

// "5.7.1 ..." 같은 Enhanced Status Code 형태를 감지합니다.
private val enhancedStatusRegex = Regex("^\\d\\.\\d\\.\\d\\b")
