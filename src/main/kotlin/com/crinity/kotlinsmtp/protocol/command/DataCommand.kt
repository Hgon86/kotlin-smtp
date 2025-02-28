package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.exception.SmtpSendResponse
import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.CoroutineInputStream
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.ERROR_IN_PROCESSING
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.START_MAIL_INPUT
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.TRANSACTION_FAILED
import com.crinity.kotlinsmtp.utils.Values.MAX_MESSAGE_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.minutes

class DataCommand : SmtpCommand(
    "DATA",
    "The text following this command is the message which should be sent.",
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size != 1) {
            respondSyntax()
        }

        session.sendResponse(START_MAIL_INPUT.code, "Start mail input - end data with <CRLF>.<CRLF>")
        session.currentMessageSize = 0 // 메시지 크기 초기화

        val dataChannel = Channel<ByteArray>(Channel.BUFFERED) // 코루틴 채널 생성
        val dataStream = CoroutineInputStream(dataChannel) // 데이터 스트림 생성

        // 별도 코루틴에서 트랜잭션 핸들러 실행
        val handlerJob = session.server.serverScope.launch {
            try {
                withTimeout(5.minutes) { // 타임아웃 설정
                    session.transactionHandler?.data(dataStream, 0)
                }
            } catch (e: TimeoutCancellationException) {
                session.sendResponse(ERROR_IN_PROCESSING.code, "Processing timeout")
            } catch (e: Exception) {
                session.sendResponse(TRANSACTION_FAILED.code, "Transaction failed: ${e.message}")
            } finally {
                dataStream.close()
            }
        }

        // 메시지 데이터 처리
        withContext(Dispatchers.IO) {
            try {
                val batchSize = 64 * 1024 // 64KB
                val batch = ByteArrayOutputStream(batchSize)

                while (true) {
                    val line = session.readLine() ?: break
                    if (line == ".") break

                    // 점으로 시작하는 라인 처리 (SMTP 도트 투명성)
                    val processedLine = if (line.startsWith(".")) line.substring(1) else line

                    // 라인을 바이트로 변환 (CRLF 포함)
                    val lineBytes = "$processedLine\r\n".toByteArray()

                    // 크기 제한 확인
                    session.currentMessageSize += lineBytes.size
                    if (session.currentMessageSize > MAX_MESSAGE_SIZE) {
                        throw SmtpSendResponse(
                            EXCEEDED_STORAGE_ALLOCATION.code,
                            "Message size exceeds limit of $MAX_MESSAGE_SIZE bytes"
                        )
                    }

                    // 데이터 배치에 추가 (이제 IO 디스패처 내에서 실행되므로 안전)
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
            } catch (e: Exception) {
                // 예외 발생 시 핸들러 작업 취소
                handlerJob.cancel()
                throw e
            } finally {
                // 채널 닫기
                dataChannel.close()
            }
        }

        // 핸들러 작업 완료 대기
        handlerJob.join()
        session.sendResponse(OKAY.code, "Ok")
    }
}
