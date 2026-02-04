package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.CoroutineInputStream
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.server.SmtpStreamingHandlerRunner
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

        // CHUNKING(BDAT) 진행 중에는 DATA를 허용하지 않습니다(실사용 클라이언트 호환/상태 일관성).
        // - BDAT를 시작한 뒤에는 BDAT ... LAST로 트랜잭션을 끝내야 합니다.
        if (session.isBdatInProgress()) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "BDAT in progress; use BDAT <size> LAST to finish")
        }

        // BINARYMIME는 DATA(도트 투명성/라인 기반)로 처리하면 의미가 없고 깨질 수 있으므로 BDAT로만 허용합니다.
        val body = session.sessionData.mailParameters["BODY"]?.uppercase()
        if (body == "BINARYMIME") {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "BINARYMIME requires BDAT (CHUNKING)")
        }

        // 상태/순서 검증: 최소 1개 이상의 RCPT 이후에만 DATA 허용
        if (session.sessionData.mailFrom == null || session.sessionData.recipientCount <= 0) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Send MAIL FROM and RCPT TO first")
        }

        // Rate Limiting: 메시지 전송 제한 검사
        val clientIp = session.clientIpAddress()
        if (clientIp != null && !session.server.rateLimiter.allowMessage(clientIp)) {
            throw SmtpSendResponse(452, "4.7.1 Too many messages from your IP. Try again later.")
        }

        session.sendResponse(START_MAIL_INPUT.code, "Start mail input - end data with <CRLF>.<CRLF>")
        session.currentMessageSize = 0 // 메시지 크기 초기화

        val dataChannel = Channel<ByteArray>(Channel.BUFFERED) // 코루틴 채널 생성
        val dataStream = CoroutineInputStream(dataChannel) // 데이터 스트림 생성
        val handlerResult = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()

        // 별도 코루틴에서 트랜잭션 핸들러 실행
        // 중요: 여기서 SMTP 응답을 보내지 않고(Result로만 반환) DATA의 최종 응답은 execute()가 "단 한 번"만 보냅니다.
        val handlerJob = SmtpStreamingHandlerRunner.launch(session, dataStream, handlerResult)

        // DATA 본문은 로그에 남기지 않도록 세션 플래그를 켭니다.
        session.inDataMode = true

        // 메시지 데이터 수신(라인 기반) → 바이트 스트림으로 변환 → 핸들러로 전달
        val receiveResult = try {
            runCatching {
                withContext(Dispatchers.IO) {
                    val batchSize = 64 * 1024 // 64KB
                    val batch = ByteArrayOutputStream(batchSize)

                    while (true) {
                        val line = session.readLine() ?: break
                        if (line == ".") break

                        // 점으로 시작하는 라인 처리 (SMTP 도트 투명성)
                        val processedLine = if (line.startsWith(".")) line.substring(1) else line

                        // 라인을 바이트로 변환 (CRLF 포함)
                    // 8BITMIME 지원을 위해 ISO-8859-1로 바이트를 1:1 보존합니다.
                    val lineBytes = "$processedLine\r\n".toByteArray(Charsets.ISO_8859_1)

                        // 크기 제한 확인
                        session.currentMessageSize += lineBytes.size

                        // SIZE 파라미터로 선언된 크기 검증 (조기 종료)
                        val declaredSize = session.sessionData.declaredSize
                        if (declaredSize != null && session.currentMessageSize > declaredSize) {
                            throw SmtpSendResponse(
                                EXCEEDED_STORAGE_ALLOCATION.code,
                                "Message size exceeds declared SIZE ($declaredSize bytes)"
                            )
                        }

                        // 전역 최대 크기 제한 확인
                        if (session.currentMessageSize > MAX_MESSAGE_SIZE) {
                            throw SmtpSendResponse(
                                EXCEEDED_STORAGE_ALLOCATION.code,
                                "Message size exceeds limit of $MAX_MESSAGE_SIZE bytes"
                            )
                        }

                        // 데이터 배치에 추가 (IO 디스패처 내에서 실행)
                        batch.write(lineBytes)

                        // 배치 크기에 도달하면 전송
                        if (batch.size() >= batchSize) {
                            dataChannel.send(batch.toByteArray())
                            batch.reset()
                        }
                    }

                    // 남은 데이터가 있으면 전송
                    if (batch.size() > 0) {
                        dataChannel.send(batch.toByteArray())
                    }
                }
            }
        } finally {
            session.inDataMode = false
        }

        // 입력 종료 → 스트림 종료
        runCatching { dataChannel.close() }

        // 수신 중 실패했다면: 프로토콜 동기화를 위해 연결을 종료(보수적)
        if (receiveResult.isFailure) {
            handlerJob.cancel()
            runCatching { dataStream.close() }
            runCatching { dataChannel.close() }
            runCatching { handlerJob.join() }

            val e = receiveResult.exceptionOrNull()!!
            when (e) {
                is SmtpSendResponse -> session.sendResponse(e.statusCode, e.message)
                else -> session.sendResponse(ERROR_IN_PROCESSING.code, "Error receiving DATA")
            }
            session.shouldQuit = true
            session.close()
            return
        }

        // 핸들러 결과 확인 (성공시에만 250)
        handlerJob.join()
        val processing = handlerResult.await()
        SmtpStreamingHandlerRunner.finalizeTransaction(session, processing)
    }
}
