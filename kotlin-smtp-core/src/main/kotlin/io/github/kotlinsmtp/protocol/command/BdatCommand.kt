package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.CoroutineInputStream
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.kotlinsmtp.utils.Values
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

/**
 * ESMTP CHUNKING (RFC 3030) - BDAT
 *
 * 형식: BDAT <chunk-size> [LAST]
 * - DATA 대신 사용 가능
 * - 각 BDAT 청크마다 250 응답을 반환(서버가 다음 청크를 받을 준비가 됐음을 의미)
 * - LAST 청크에서는 전체 메시지 처리가 완료된 뒤 최종 250/4xx/5xx를 반환
 */
internal class BdatCommand : SmtpCommand(
    "BDAT",
    "Chunking - sends a message as one or more chunks. Syntax: BDAT <chunk-size> [LAST]",
    "<chunk-size> [LAST]"
) {
    /**
     * peerAddress에서 클라이언트 IP 추출
     * 형식: \"hostname [1.2.3.4]:port\" 또는 \"1.2.3.4:port\"
     */
    private fun extractClientIp(peerAddress: String?): String? {
        if (peerAddress == null) return null
        val bracketMatch = Regex("\\[([^\\]]+)\\]").find(peerAddress)
        if (bracketMatch != null) return bracketMatch.groupValues[1]
        return peerAddress.substringBefore(':').trim()
    }

    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size !in 2..3) {
            respondSyntax()
        }

        val chunkSize = command.parts[1].toLongOrNull()
            ?: run {
                // BDAT는 바로 뒤에 raw bytes가 이어질 수 있어, 파싱 불가 시 동기화가 어렵습니다.
                // 따라서 501 응답 후 연결을 종료합니다(보수적).
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
            // 너무 큰 청크는 읽기 전에 차단(메모리 폭주 방지)
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

        // BINARYMIME을 선언한 트랜잭션은 BDAT만으로 본문을 받아야 합니다(이미 DATA에서 거부).
        // - 여기서는 별도 처리는 하지 않되, 명시적으로 주석으로 남겨 혼용을 방지합니다.

        val isFirstChunk = (session.bdatDataChannel == null)
        if (isFirstChunk) {
            session.currentMessageSize = 0
        }

        // IMPORTANT:
        // BDAT는 "명령 라인 + 바로 뒤의 raw bytes"가 한 번에 들어올 수 있습니다.
        // 서버는 프로토콜 동기화를 위해, 오류 응답을 하더라도 지정 바이트를 '반드시 소비(drain)'해야 합니다.
        val bytes = session.readBytesExact(chunkSize.toInt())
            ?: run {
                // 연결이 끊긴 경우: 스트림 정리 후 종료
                session.clearBdatState()
                session.shouldQuit = true
                session.close()
                return
            }

        // 상태/순서 검증(드레인 이후에 수행)
        if (session.sessionData.mailFrom == null || session.sessionData.recipientCount <= 0) {
            // 수신한 청크는 폐기하고, 트랜잭션은 리셋합니다.
            session.clearBdatState()
            session.resetTransaction(preserveGreeting = true)
            throw SmtpSendResponse(SmtpStatusCode.BAD_COMMAND_SEQUENCE.code, "Send MAIL FROM and RCPT TO first")
        }

        // 첫 BDAT 청크에서만 Rate Limiting 메시지 제한을 소모합니다.
        if (isFirstChunk) {
            val clientIp = extractClientIp(session.sessionData.peerAddress)
            if (clientIp != null && !session.server.rateLimiter.allowMessage(clientIp)) {
                session.clearBdatState()
                session.resetTransaction(preserveGreeting = true)
                throw SmtpSendResponse(452, "4.7.1 Too many messages from your IP. Try again later.")
            }
        }

        // 크기 제한(선언 SIZE 및 서버 최대): 초과 시 청크 폐기 후 오류 응답
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

        // 스트리밍 상태 초기화(첫 청크) - 여기부터는 '유효한 트랜잭션'으로 처리합니다.
        if (session.bdatDataChannel == null) {
            val dataChannel = Channel<ByteArray>(Channel.BUFFERED)
            val dataStream = CoroutineInputStream(dataChannel)
            val handlerResult = CompletableDeferred<Result<Unit>>()

            val handlerJob = session.server.serverScope.launch {
                val result = runCatching {
                    withTimeout(5.minutes) {
                        val handler = session.transactionHandler
                            ?: throw SmtpSendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "No transaction handler configured")
                        handler.data(dataStream, 0)
                    }
                }.map { Unit }

                handlerResult.complete(result)
                runCatching { dataStream.close() }
            }

            session.bdatDataChannel = dataChannel
            session.bdatStream = dataStream
            session.bdatHandlerResult = handlerResult
            session.bdatHandlerJob = handlerJob
        }

        session.currentMessageSize = proposedTotal.toInt()

        // 청크를 핸들러로 전달
        val dataChannel = session.bdatDataChannel
            ?: throw SmtpSendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "BDAT internal state error")

        // IO 디스패처에서 batch 처리(추후 청크 분할/스풀링 최적화 여지)
        runCatching {
            withContext(Dispatchers.IO) {
                // 작은 청크는 그대로, 큰 청크도 한 번에 전달(청크 상한으로 메모리 폭주 방지)
                // BDAT 0 케이스는 불필요한 전송을 피합니다.
                if (bytes.isNotEmpty()) dataChannel.send(bytes)
            }
        }.onFailure { t ->
            session.clearBdatState()
            throw SmtpSendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Error receiving BDAT: ${t.message ?: "unknown"}")
        }

        if (!isLast) {
            // 중간 청크: 다음 BDAT를 받을 준비 완료
            session.sendResponse(SmtpStatusCode.OKAY.code, "Ok")
            return
        }

        // LAST 청크: 입력 종료 → 스트림 종료 → 처리 결과에 따라 최종 응답
        runCatching { dataChannel.close() }

        val handlerJob = session.bdatHandlerJob
        val handlerResult = session.bdatHandlerResult
            ?: throw SmtpSendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "BDAT internal state error")

        handlerJob?.join()
        val processing = handlerResult.await()

        // 상태 정리(성공/실패 공통)
        session.clearBdatState()

        if (processing.isFailure) {
            val e = processing.exceptionOrNull()!!
            when (e) {
                is TimeoutCancellationException ->
                    session.sendResponse(SmtpStatusCode.ERROR_IN_PROCESSING.code, "Processing timeout")

                is SmtpSendResponse ->
                    session.sendResponse(e.statusCode, e.message)

                else ->
                    session.sendResponse(SmtpStatusCode.TRANSACTION_FAILED.code, "Transaction failed: ${e.message ?: "unknown"}")
            }
            session.resetTransaction(preserveGreeting = true)
            return
        }

        session.resetTransaction(preserveGreeting = true)
        session.sendResponse(SmtpStatusCode.OKAY.code, "Ok")
    }
}
