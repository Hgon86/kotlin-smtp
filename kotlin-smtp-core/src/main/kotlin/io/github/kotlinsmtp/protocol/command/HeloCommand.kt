package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY

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
        session.sessionData.greeted = true
        session.sessionData.usedEhlo = false

        // 확장 코드 없이 응답 전송
        session.respondLine("${OKAY.code} ${session.server.hostname} at your service")
    }
}
