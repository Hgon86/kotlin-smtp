package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.COMMAND_NOT_IMPLEMENTED
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY

class StartTlsCommand : SmtpCommand(
    "STARTTLS",
    "Upgrade connection to TLS",
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (session.isTls) {
            // 이미 TLS 연결 상태면 오류 응답
            session.sendResponse(COMMAND_NOT_IMPLEMENTED.code, "TLS already active")
            return
        }

        // TLS 업그레이드 시작 응답
        session.sendResponse(OKAY.code, "Ready to start TLS")

        // TLS 핸드셰이크 시작
        session.startTls()
    }
}
