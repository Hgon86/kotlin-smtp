package com.crinity.kotlinsmtp.server

import com.crinity.kotlinsmtp.model.SessionData
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommands
import com.crinity.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import com.crinity.kotlinsmtp.utils.SmtpStatusCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.handler.ssl.SslHandler
import io.netty.util.CharsetUtil
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.*
import kotlinx.coroutines.channels.Channel as KChannel

private val log = KotlinLogging.logger {}

class SmtpSession(
    private val channel: Channel,
    val server: SmtpServer,
) {
    private val incomingLines = KChannel<String>(KChannel.UNLIMITED)
    private val sessionActive = MutableStateFlow(true)
    private val sessionId = UUID.randomUUID().toString().take(8)

    var shouldQuit = false
    var sessionData = SessionData(); internal set
    var currentMessageSize = 0; internal set // 현재 메시지 크기 추적

    var transactionHandler: SmtpProtocolHandler? = null
        get() {
            if (field == null && server.transactionHandlerCreator != null) {
                val handler = server.transactionHandlerCreator.invoke()
                handler.init(sessionData)
                field = handler
            }
            return field
        }

    var isTls: Boolean = false
        private set

    suspend fun handle() {
        try {
            sendResponse(220, "${server.hostname} ${server.serviceName} Service ready")

            while (!shouldQuit && sessionActive.value) {
                val line = readLine()
                if (line != null) {
                    SmtpCommands.handle(line, this)
                } else {
                    break
                }
            }
        } finally {
            channel.close()
        }
    }

    internal suspend fun readLine(): String? = incomingLines.receiveCatching().getOrNull()

    internal suspend fun respondLine(message: String) {
        val response = Unpooled.copiedBuffer("$message\r\n", CharsetUtil.UTF_8)
        channel.writeAndFlush(response)
        log.info { "Session[$sessionId] <- $message" }
    }

    suspend fun sendResponse(code: Int, message: String? = null) {
        val statusCode = SmtpStatusCode.fromCode(code)
        val response = statusCode?.formatResponse(message) ?: "$code $message"

        respondLine(response)
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

    suspend fun resetTransaction() {
        transactionHandler?.done()
        transactionHandler = null
        sessionData = SessionData()
        currentMessageSize = 0  // 메시지 크기 리셋
    }

    fun close() {
        sessionActive.value = false
        channel.close()
    }

    suspend fun handleIncomingLine(line: String) {
        log.info { "Session[$sessionId] -> $line" }
        incomingLines.send(line)
    }

    suspend fun startTls() {
        val sslContext = server.sslContext ?: return

        // 기존 핸들러 제거 (StringEncoder/Decoder 등)
        val pipeline = channel.pipeline()
        val handlers = listOf("stringDecoder", "stringEncoder", "frameDecoder")
        handlers.forEach { name ->
            if (pipeline[name] != null) {
                pipeline.remove(name)
            }
        }

        // SSL 핸들러 추가
        val sslEngine = sslContext.newEngine(channel.alloc())
        sslEngine.useClientMode = false
        pipeline.addFirst("ssl", SslHandler(sslEngine))

        // 문자열 인코더/디코더 다시 추가
        pipeline.addAfter("ssl", "frameDecoder", io.netty.handler.codec.LineBasedFrameDecoder(8192))
        pipeline.addAfter(
            "frameDecoder",
            "stringDecoder",
            io.netty.handler.codec.string.StringDecoder(io.netty.util.CharsetUtil.UTF_8)
        )
        pipeline.addAfter(
            "stringDecoder",
            "stringEncoder",
            io.netty.handler.codec.string.StringEncoder(io.netty.util.CharsetUtil.UTF_8)
        )

        isTls = true
    }
}
