package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.spi.SmtpMessageAcceptedEvent
import io.github.kotlinsmtp.spi.SmtpMessageEnvelope
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
 * DATA/BDAT 본문 스트리밍 처리에서 공통으로 사용하는 핸들러 실행/결과 응답 유틸입니다.
 */
internal object SmtpStreamingHandlerRunner {

    /**
     * 트랜잭션 핸들러의 data()를 별도 코루틴에서 실행하고 결과를 deferred에 기록합니다.
     *
     * @param timeout 본문 처리 최대 시간
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
     * 핸들러 실행 결과에 따라 SMTP 응답을 보내고 트랜잭션 상태를 리셋합니다.
     *
     * @return 성공 처리면 true
     */
    suspend fun finalizeTransaction(
        session: SmtpSession,
        processing: Result<Unit>,
        transferMode: SmtpMessageTransferMode,
    ): Boolean {
        val envelope = SmtpMessageEnvelope(
            mailFrom = session.sessionData.mailFrom ?: "",
            rcptTo = session.envelopeRecipients.toList(),
            dsnEnvid = session.sessionData.dsnEnvid,
            dsnRet = session.sessionData.dsnRet,
            rcptDsn = session.sessionData.rcptDsnView,
        )

        val context = session.buildSessionContext()
        val sizeBytes = session.currentMessageSize.toLong()

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
            session.resetTransaction(preserveGreeting = true)
            return false
        }

        session.sendResponse(SmtpStatusCode.OKAY.code, "Ok")

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

        session.resetTransaction(preserveGreeting = true)
        return true
    }
}
