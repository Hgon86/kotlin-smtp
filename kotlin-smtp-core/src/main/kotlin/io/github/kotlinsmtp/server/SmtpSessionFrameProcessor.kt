package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.kotlinsmtp.utils.Values
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.channels.Channel as KChannel

private val frameLog = KotlinLogging.logger {}

/**
 * Separates inbound frame processing responsibility for SMTP sessions.
 *
 * @property sessionId Session identifier
 * @property incomingFrames Incoming frame queue
 * @property backpressure Backpressure controller
 * @property channel Network channel
 * @property logSanitizer Log masking helper
 * @property responseFormatter Response formatter
 * @property inDataMode DATA receiving mode accessor
 * @property getDataModeFramingHint DATA framing hint accessor
 * @property setDataModeFramingHint DATA framing hint mutator
 * @property failAndClose Callback to terminate after error response
 * @property sendResponse Callback to send immediate response
 * @property sendResponseAwait Callback to send response with flush guarantee
 * @property isClosing Accessor for session-closing state
 * @property isTlsUpgrading Accessor for STARTTLS upgrade state
 * @property closeOnly Callback to close without response
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
     * Read next line frame and return as string.
     *
     * @return Received line, or null on termination
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
     * Read byte frame while expecting exact byte count.
     *
     * @param expectedBytes Expected byte count
     * @return Received bytes, or null on termination
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
     * Enqueue inbound frame into session queue.
     *
     * @param frame Inbound frame to enqueue
     * @return Whether enqueue succeeded
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
     * Adjust backpressure counters for frame discarded from queue.
     *
     * @param frame Discarded frame
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
