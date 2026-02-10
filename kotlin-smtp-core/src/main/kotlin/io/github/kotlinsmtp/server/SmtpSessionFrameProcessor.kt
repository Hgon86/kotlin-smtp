package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.kotlinsmtp.utils.Values
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.channels.Channel as KChannel

private val frameLog = KotlinLogging.logger {}

/**
 * SMTP 세션의 인바운드 프레임 처리 책임을 분리합니다.
 *
 * @property sessionId 세션 식별자
 * @property incomingFrames 수신 프레임 큐
 * @property backpressure 백프레셔 제어기
 * @property channel 네트워크 채널
 * @property logSanitizer 로그 마스킹 도우미
 * @property responseFormatter 응답 포맷터
 * @property inDataMode DATA 수신 모드 조회기
 * @property getDataModeFramingHint DATA 프레이밍 힌트 조회기
 * @property setDataModeFramingHint DATA 프레이밍 힌트 변경기
 * @property failAndClose 오류 응답 후 종료 콜백
 * @property sendResponse 즉시 응답 전송 콜백
 * @property sendResponseAwait flush 보장 응답 전송 콜백
 * @property isClosing 세션 종료 중 여부 조회기
 * @property isTlsUpgrading STARTTLS 전환 중 여부 조회기
 * @property closeOnly 응답 없이 종료 콜백
 */
internal class SmtpSessionFrameProcessor(
    private val sessionId: String,
    private val incomingFrames: KChannel<SmtpInboundFrame>,
    private val backpressure: SmtpBackpressureController,
    private val channel: Channel,
    private val logSanitizer: SmtpSessionLogSanitizer,
    private val responseFormatter: SmtpResponseFormatter,
    private val inDataMode: () -> Boolean,
    private val getDataModeFramingHint: () -> Boolean,
    private val setDataModeFramingHint: (Boolean) -> Unit,
    private val failAndClose: () -> Unit,
    private val sendResponse: suspend (Int, String) -> Unit,
    private val sendResponseAwait: suspend (Int, String) -> Unit,
    private val isClosing: () -> Boolean,
    private val isTlsUpgrading: () -> Boolean,
    private val closeOnly: () -> Unit,
) {
    /**
     * 다음 라인 프레임을 읽어 문자열로 반환합니다.
     *
     * @return 수신 라인 또는 종료 시 null
     */
    suspend fun readLine(): String? {
        val frame = incomingFrames.receiveCatching().getOrNull() ?: return null
        return when (frame) {
            is SmtpInboundFrame.Line -> {
                backpressure.onConsumed(backpressure.estimateLineBytes(frame.text))
                val line = frame.text

                frameLog.info {
                    "Session[$sessionId] -> ${logSanitizer.sanitize(line, inDataMode(), getDataModeFramingHint())}"
                }

                updateDataModeFramingHint(line)

                val maxAllowed = if (inDataMode() || getDataModeFramingHint()) {
                    Values.MAX_SMTP_LINE_LENGTH
                } else {
                    Values.MAX_COMMAND_LINE_LENGTH
                }

                if (line.length > maxAllowed) {
                    sendResponseAwait(
                        SmtpStatusCode.COMMAND_SYNTAX_ERROR.code,
                        "Line too long (max $maxAllowed bytes)",
                    )
                    failAndClose()
                    return null
                }

                line
            }
            is SmtpInboundFrame.Bytes -> {
                backpressure.releaseInflightBdatBytes(frame.bytes.size)
                backpressure.onConsumed(frame.bytes.size.toLong())
                sendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Protocol sync error")
                failAndClose()
                null
            }
        }
    }

    /**
     * 정확한 바이트 수를 기대하고 바이트 프레임을 읽습니다.
     *
     * @param expectedBytes 기대 바이트 수
     * @return 수신 바이트 또는 종료 시 null
     */
    suspend fun readBytesExact(expectedBytes: Int): ByteArray? {
        val frame = incomingFrames.receiveCatching().getOrNull() ?: return null
        return when (frame) {
            is SmtpInboundFrame.Bytes -> frame.bytes.also {
                backpressure.releaseInflightBdatBytes(it.size)
                backpressure.onConsumed(it.size.toLong())
                if (it.size != expectedBytes) {
                    sendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Protocol sync error")
                    failAndClose()
                }
            }
            is SmtpInboundFrame.Line -> {
                backpressure.onConsumed(backpressure.estimateLineBytes(frame.text))
                sendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Protocol sync error")
                failAndClose()
                null
            }
        }
    }

    /**
     * 수신 프레임을 세션 큐에 적재합니다.
     *
     * @param frame 적재할 인바운드 프레임
     * @return 적재 성공 여부
     */
    fun tryEnqueueInboundFrame(frame: SmtpInboundFrame): Boolean {
        if (isClosing()) return false
        if (isTlsUpgrading()) {
            closeOnly()
            return false
        }

        return when (frame) {
            is SmtpInboundFrame.Line -> enqueueLine(frame)
            is SmtpInboundFrame.Bytes -> enqueueBytes(frame)
        }
    }

    /**
     * 큐에서 폐기한 프레임의 백프레셔 카운터를 보정합니다.
     *
     * @param frame 폐기 프레임
     */
    fun discardQueuedFrame(frame: SmtpInboundFrame) {
        when (frame) {
            is SmtpInboundFrame.Line -> backpressure.onConsumed(backpressure.estimateLineBytes(frame.text))
            is SmtpInboundFrame.Bytes -> {
                backpressure.releaseInflightBdatBytes(frame.bytes.size)
                backpressure.onConsumed(frame.bytes.size.toLong())
            }
        }
    }

    private fun updateDataModeFramingHint(line: String) {
        val commandPart = line.trimStart()
        if (!inDataMode() && !getDataModeFramingHint() && commandPart.equals("DATA", ignoreCase = true)) {
            setDataModeFramingHint(true)
        } else if (getDataModeFramingHint() && line == ".") {
            setDataModeFramingHint(false)
        }
    }

    private fun enqueueLine(frame: SmtpInboundFrame.Line): Boolean {
        val lineBytes = backpressure.estimateLineBytes(frame.text)
        backpressure.onQueued(lineBytes)

        val result = incomingFrames.trySend(frame)
        if (result.isFailure) {
            backpressure.onConsumed(lineBytes)
            return closeWithOverflow()
        }
        return true
    }

    private fun enqueueBytes(frame: SmtpInboundFrame.Bytes): Boolean {
        val size = frame.bytes.size
        if (!backpressure.tryReserveInflightBdatBytes(size)) {
            return closeWithOverflow()
        }

        backpressure.onQueued(size.toLong())
        val result = incomingFrames.trySend(frame)
        if (result.isFailure) {
            backpressure.releaseInflightBdatBytes(size)
            backpressure.onConsumed(size.toLong())
            return closeWithOverflow()
        }

        frameLog.debug { "Session[$sessionId] -> <BYTES:${size} bytes>" }
        return true
    }

    private fun closeWithOverflow(): Boolean {
        val responseLine = responseFormatter.formatLine(
            SmtpStatusCode.SERVICE_NOT_AVAILABLE.code,
            "Input buffer overflow. Closing connection.",
        )
        channel.writeAndFlush("$responseLine\r\n").addListener(ChannelFutureListener.CLOSE)
        return false
    }
}
