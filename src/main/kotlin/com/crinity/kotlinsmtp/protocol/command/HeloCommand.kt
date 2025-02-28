package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY

class HeloCommand : SmtpCommand(
    "HELO",
    "The client introduces itself.",
    "<domain>"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size < 2) {
            respondSyntax("Empty HELO/EHLO argument not allowed.")
        }

        session.resetTransaction()
        session.sessionData.helo = command.parts[1]

        // 확장 코드 없이 응답 전송
        session.respondLine("${OKAY.code} ${session.server.hostname} at your service")
    }
}
