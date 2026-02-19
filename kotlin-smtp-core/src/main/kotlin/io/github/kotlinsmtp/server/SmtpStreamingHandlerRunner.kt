package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.spi.SmtpMessageAcceptedEvent
import io.github.kotlinsmtp.spi.SmtpMessageRejectedEvent
import io.github.kotlinsmtp.spi.SmtpMessageStage
import io.github.kotlinsmtp.spi.SmtpMessageTransferMode
import io.github.kotlinsmtp.utils.SmtpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Utility for shared handler execution/result response used in DATA/BDAT body streaming.
 */
internal object SmtpStreamingHandlerRunner {

    /**
     * Run transaction handler data() in a separate coroutine and record result into deferred.
     *
     * @param timeout Maximum body processing time
     */
    fun launch(
        session: SmtpSession,
        dataStream: CoroutineInputStream,
        handlerResult: CompletableDeferred<Result<Unit>>,
        timeout: Duration = 5.minutes,
    ) = session.server.serverScope.launch {
        val result = runCatching {
            withTimeout(timeout) {
                val handler = session.transactionHandler
                    ?: throw SmtpSendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "No transaction handler configured")
                handler.data(dataStream, 0)
            }
        }.map { Unit }

        handlerResult.complete(result)
        runCatching { dataStream.close() }
    }

    /**
     * Send SMTP response and reset transaction state based on handler execution result.
     *
     * @return true when processed successfully
     */
    suspend fun finalizeTransaction(
        session: SmtpSession,
        processing: Result<Unit>,
        transferMode: SmtpMessageTransferMode,
    ): Boolean {
        val shouldNotify = session.server.hasEventHooks()

        if (processing.isFailure) {
            val (code, message) = when (val e = processing.exceptionOrNull()!!) {
                is TimeoutCancellationException ->
                    SmtpStatusCode.ERROR_IN_PROCESSING.code to "Processing timeout"

                is SmtpSendResponse ->
                    e.statusCode to e.message

                else ->
                    SmtpStatusCode.TRANSACTION_FAILED.code to "Transaction failed"
            }

            session.sendResponse(code, message)
            if (shouldNotify) {
                val context = session.buildSessionContext()
                val envelope = session.buildMessageEnvelopeSnapshot()
                session.server.notifyHooks { hook ->
                    hook.onMessageRejected(
                        SmtpMessageRejectedEvent(
                            context = context,
                            envelope = envelope,
                            transferMode = transferMode,
                            stage = SmtpMessageStage.PROCESSING,
                            responseCode = code,
                            responseMessage = message,
                        )
                    )
                }
            }
            session.resetTransaction(preserveGreeting = true)
            return false
        }

        session.sendResponse(SmtpStatusCode.OKAY.code, "Ok")

        if (shouldNotify) {
            val context = session.buildSessionContext()
            val envelope = session.buildMessageEnvelopeSnapshot()
            val sizeBytes = session.currentMessageSize.toLong()
            session.server.notifyHooks { hook ->
                hook.onMessageAccepted(
                    SmtpMessageAcceptedEvent(
                        context = context,
                        envelope = envelope,
                        transferMode = transferMode,
                        sizeBytes = sizeBytes,
                    )
                )
            }
        }

        session.resetTransaction(preserveGreeting = true)
        return true
    }
}
