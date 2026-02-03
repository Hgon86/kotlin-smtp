package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.COMMAND_NOT_IMPLEMENTED
import io.github.kotlinsmtp.utils.SmtpStatusCode.SERVICE_READY

internal class StartTlsCommand : SmtpCommand(
    "STARTTLS",
    "Upgrade connection to TLS",
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (session.isTls) {
            // 이미 TLS 연결 상태면 오류 응답
            session.sendResponse(503, "5.5.1 TLS already active")
            return
        }

        if (!session.server.enableStartTls) {
            session.sendResponse(COMMAND_NOT_IMPLEMENTED.code, "5.5.1 STARTTLS not supported on this service")
            return
        }

        // 서버가 TLS를 지원하지 않으면 명령 거부
        if (session.server.sslContext == null) {
            session.sendResponse(454, "4.7.0 TLS not available")
            return
        }

        // RFC 3207 관례: 220 Ready to start TLS
        // 중요: 이 라인은 반드시 "평문으로 flush 완료"된 뒤에 파이프라인에 SslHandler를 삽입해야 합니다.
        session.sendResponseAwait(SERVICE_READY.code, "Ready to start TLS")

        // TLS 핸드셰이크 시작 및 파이프라인 갱신
        session.startTls()
    }
}
