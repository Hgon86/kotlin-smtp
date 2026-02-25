package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.CoroutineInputStream
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.server.SmtpStreamingHandlerRunner
import io.github.kotlinsmtp.spi.SmtpMessageStage
import io.github.kotlinsmtp.spi.SmtpMessageTransferMode
import io.github.kotlinsmtp.utils.SmtpStatusCode.ERROR_IN_PROCESSING
import io.github.kotlinsmtp.utils.SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY
import io.github.kotlinsmtp.utils.SmtpStatusCode.START_MAIL_INPUT
import io.github.kotlinsmtp.utils.SmtpStatusCode.TRANSACTION_FAILED
import io.github.kotlinsmtp.utils.SmtpStatusCode.BAD_COMMAND_SEQUENCE
import io.github.kotlinsmtp.utils.Values.MAX_MESSAGE_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

internal class DataCommand : SmtpCommand(
    "DATA",
    "The text following this command is the message which should be sent.",
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size != 1) {
            respondSyntax()
        }

        // Do not allow DATA while CHUNKING (BDAT) is in progress (real-world client compatibility/state consistency).
        // - After starting BDAT, the transaction must be finished with BDAT ... LAST.
        if (session.isBdatInProgress()) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "BDAT in progress; use BDAT <size> LAST to finish")
        }

        // BINARYMIME can be meaningless/broken with DATA (dot transparency/line-based), so allow only with BDAT.
        val body = session.sessionData.mailParameters["BODY"]?.uppercase()
        if (body == "BINARYMIME") {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "BINARYMIME requires BDAT (CHUNKING)")
        }

        // Rate limiting: check message send limit.
        val clientIp = session.clientIpAddress()
        if (clientIp != null && !session.server.rateLimiter.allowMessage(clientIp)) {
            throw SmtpSendResponse(452, "4.7.1 Too many messages from your IP. Try again later.")
        }

        session.sendResponse(START_MAIL_INPUT.code, "Start mail input - end data with <CRLF>.<CRLF>")
        session.currentMessageSize = 0 // Reset message size

        val dataChannel = Channel<ByteArray>(Channel.BUFFERED) // Create coroutine channel
        val dataStream = CoroutineInputStream(dataChannel) // Create data stream
        val processorResult = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()

        // Run transaction processor in a separate coroutine.
        // Important: do not send SMTP response here (return as Result only); execute() sends the final DATA response exactly once.
        val processorJob = SmtpStreamingHandlerRunner.launch(session, dataStream, processorResult)

        // Enable session flag so DATA body is not logged.
        session.inDataMode = true

        // Receive message data (line-based) -> convert to byte stream -> pass to transaction processor.
        val receiveResult = try {
            runCatching {
                withContext(Dispatchers.IO) {
                    val batchSize = 64 * 1024 // 64KB
                    val batch = ByteArrayOutputStream(batchSize)

                    while (true) {
                        val line = session.readLine() ?: break
                        if (line == ".") break

                        // Process lines starting with dot (SMTP dot transparency)
                        val processedLine = if (line.startsWith(".")) line.substring(1) else line

                        // Convert line to bytes (including CRLF)
                        // Preserve bytes 1:1 with ISO-8859-1 for 8BITMIME support.
                        val lineBytes = "$processedLine\r\n".toByteArray(Charsets.ISO_8859_1)

                        // Check size limit
                        session.currentMessageSize += lineBytes.size

                        // Validate size declared by SIZE parameter (early termination)
                        val declaredSize = session.sessionData.declaredSize
                        if (declaredSize != null && session.currentMessageSize > declaredSize) {
                            throw SmtpSendResponse(
                                EXCEEDED_STORAGE_ALLOCATION.code,
                                "Message size exceeds declared SIZE ($declaredSize bytes)"
                            )
                        }

                        // Check global max size limit
                        if (session.currentMessageSize > MAX_MESSAGE_SIZE) {
                            throw SmtpSendResponse(
                                EXCEEDED_STORAGE_ALLOCATION.code,
                                "Message size exceeds limit of $MAX_MESSAGE_SIZE bytes"
                            )
                        }

                        // Add to data batch (runs in IO dispatcher)
                        batch.write(lineBytes)

                        // Send when batch size is reached
                        if (batch.size() >= batchSize) {
                            dataChannel.send(batch.toByteArray())
                            batch.reset()
                        }
                    }

                    // Send if any data remains
                    if (batch.size() > 0) {
                        dataChannel.send(batch.toByteArray())
                    }
                }
            }
        } finally {
            session.inDataMode = false
        }

        // Input complete -> close stream
        runCatching { dataChannel.close() }

        // If receiving failed: close connection for protocol synchronization (conservative)
        if (receiveResult.isFailure) {
            processorJob.cancel()
            runCatching { dataStream.close() }
            runCatching { dataChannel.close() }
            runCatching { processorJob.join() }

            val e = receiveResult.exceptionOrNull()!!

            val (code, message) = when (e) {
                is SmtpSendResponse -> e.statusCode to e.message
                else -> ERROR_IN_PROCESSING.code to "Error receiving DATA"
            }

            session.sendResponse(code, message)
            session.notifyMessageRejected(
                transferMode = SmtpMessageTransferMode.DATA,
                stage = SmtpMessageStage.RECEIVING,
                responseCode = code,
                responseMessage = message,
            )
            session.shouldQuit = true
            session.close()
            return
        }

        // Check processor result (250 only on success)
        processorJob.join()
        val processing = processorResult.await()
        SmtpStreamingHandlerRunner.finalizeTransaction(
            session = session,
            processing = processing,
            transferMode = SmtpMessageTransferMode.DATA,
        )
    }
}
