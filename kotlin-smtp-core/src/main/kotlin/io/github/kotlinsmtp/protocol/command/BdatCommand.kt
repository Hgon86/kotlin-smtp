package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.CoroutineInputStream
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.server.SmtpStreamingHandlerRunner
import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.kotlinsmtp.utils.Values
import io.github.kotlinsmtp.spi.SmtpMessageStage
import io.github.kotlinsmtp.spi.SmtpMessageTransferMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * ESMTP CHUNKING (RFC 3030) - BDAT
 *
 * Format: BDAT <chunk-size> [LAST]
 * - Can be used instead of DATA
 * - Returns 250 for each BDAT chunk (meaning server is ready for next chunk)
 * - For LAST chunk, returns final 250/4xx/5xx after full message processing
 */
internal class BdatCommand : SmtpCommand(
    "BDAT",
    "Chunking - sends a message as one or more chunks. Syntax: BDAT <chunk-size> [LAST]",
    "<chunk-size> [LAST]"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size !in 2..3) {
            respondSyntax()
        }

        val chunkSize = command.parts[1].toLongOrNull()
            ?: run {
                // BDAT may be immediately followed by raw bytes, making synchronization difficult when parsing fails.
                // Therefore respond 501 and close connection (conservative).
                session.sendResponse(SmtpStatusCode.COMMAND_SYNTAX_ERROR.code, "Invalid BDAT chunk-size")
                session.shouldQuit = true
                session.close()
                return
            }
        if (chunkSize < 0) {
            session.sendResponse(SmtpStatusCode.COMMAND_SYNTAX_ERROR.code, "Invalid BDAT chunk-size")
            session.shouldQuit = true
            session.close()
            return
        }
        if (chunkSize > Values.MAX_BDAT_CHUNK_SIZE.toLong()) {
            // Reject excessively large chunks before reading (prevent memory blow-up)
            session.sendResponse(
                SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION.code,
                "BDAT chunk too large (max ${Values.MAX_BDAT_CHUNK_SIZE} bytes)"
            )
            session.shouldQuit = true
            session.close()
            return
        }

        val isLast = command.parts.getOrNull(2)?.equals("LAST", ignoreCase = true) == true
        if (command.parts.size == 3 && !isLast) {
            session.sendResponse(SmtpStatusCode.COMMAND_SYNTAX_ERROR.code, "Invalid BDAT parameter")
            session.shouldQuit = true
            session.close()
            return
        }

        // Transactions that declared BINARYMIME must receive body only via BDAT (already rejected in DATA).
        // - No additional handling here; keep explicit note to prevent mixed usage.

        val isFirstChunk = !session.bdatState.isActive
        if (isFirstChunk) {
            session.currentMessageSize = 0
        }

        // IMPORTANT:
        // BDAT can arrive as "command line + immediate raw bytes" in one shot.
        // For protocol synchronization, the server must drain the specified bytes even when responding with error.
        val bytes = session.readBytesExact(chunkSize.toInt())
            ?: run {
                // Connection closed: clean stream and terminate
                session.clearBdatState()
                session.shouldQuit = true
                session.close()
                return
            }

        // Validate state/sequence (performed after drain)
        if (session.sessionData.mailFrom == null || session.sessionData.recipientCount <= 0) {
            // Discard received chunk and reset transaction.
            session.clearBdatState()
            session.resetTransaction(preserveGreeting = true)
            throw SmtpSendResponse(SmtpStatusCode.BAD_COMMAND_SEQUENCE.code, "Send MAIL FROM and RCPT TO first")
        }

        // Consume rate-limited message quota only on first BDAT chunk.
        if (isFirstChunk) {
            val clientIp = session.clientIpAddress()
            if (clientIp != null && !session.server.rateLimiter.allowMessage(clientIp)) {
                session.clearBdatState()
                session.resetTransaction(preserveGreeting = true)
                throw SmtpSendResponse(452, "4.7.1 Too many messages from your IP. Try again later.")
            }
        }

        // Size limits (declared SIZE and server max): discard chunk and return error when exceeded
        val proposedTotal = session.currentMessageSize.toLong() + bytes.size.toLong()
        val declaredSize = session.sessionData.declaredSize
        if (declaredSize != null && proposedTotal > declaredSize) {
            session.clearBdatState()
            session.resetTransaction(preserveGreeting = true)
            throw SmtpSendResponse(
                SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION.code,
                "Message size exceeds declared SIZE ($declaredSize bytes)"
            )
        }
        if (proposedTotal > Values.MAX_MESSAGE_SIZE.toLong()) {
            session.clearBdatState()
            session.resetTransaction(preserveGreeting = true)
            throw SmtpSendResponse(
                SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION.code,
                "Message size exceeds limit of ${Values.MAX_MESSAGE_SIZE} bytes"
            )
        }

        // Initialize streaming state (first chunk) - from here it is treated as a valid transaction.
        if (!session.bdatState.isActive) {
            val dataChannel = Channel<ByteArray>(Channel.BUFFERED)
            val dataStream = CoroutineInputStream(dataChannel)
            val handlerResult = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
            val handlerJob = SmtpStreamingHandlerRunner.launch(session, dataStream, handlerResult)

            session.bdatState.start(
                dataChannel = dataChannel,
                stream = dataStream,
                handlerJob = handlerJob,
                handlerResult = handlerResult,
            )
        }

        session.currentMessageSize = proposedTotal.toInt()

        // Deliver chunk to handler
        val dataChannel = session.bdatState.dataChannel
            ?: throw SmtpSendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "BDAT internal state error")

        // Batch processing in IO dispatcher (room for future chunk split/spooling optimization)
        val sendChunk = runCatching {
            withContext(Dispatchers.IO) {
                // Send small chunks as-is, and large chunks at once too (chunk cap prevents memory blow-up)
                // Avoid unnecessary send for BDAT 0 case.
                if (bytes.isNotEmpty()) dataChannel.send(bytes)
            }
        }
        if (sendChunk.isFailure) {
            // For protocol synchronization: close connection after error response (conservative)
            session.clearBdatState()
            val code = SmtpStatusCode.ERROR_IN_PROCESSING.code
            val message = "Error receiving BDAT"
            session.sendResponse(code, message)

            session.notifyMessageRejected(
                transferMode = SmtpMessageTransferMode.BDAT,
                stage = SmtpMessageStage.RECEIVING,
                responseCode = code,
                responseMessage = message,
            )

            session.shouldQuit = true
            session.close()
            return
        }

        if (!isLast) {
            // Intermediate chunk: ready to receive next BDAT
            session.sendResponse(SmtpStatusCode.OKAY.code, "Ok")
            return
        }

        // LAST chunk: end input -> close stream -> final response by processing result
        runCatching { dataChannel.close() }

        val handlerJob = session.bdatState.handlerJob
        val handlerResult = session.bdatState.handlerResult
            ?: throw SmtpSendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "BDAT internal state error")

        handlerJob?.join()
        val processing = handlerResult.await()

        // Cleanup state (common for success/failure)
        session.clearBdatState()
        SmtpStreamingHandlerRunner.finalizeTransaction(
            session = session,
            processing = processing,
            transferMode = SmtpMessageTransferMode.BDAT,
        )
    }
}
