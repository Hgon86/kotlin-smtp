package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY


internal class RsetCommand : SmtpCommand(
    "RSET",
    "Resets the current session."
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size != 1)
            respondSyntax()

        session.resetTransaction()

        session.sendResponse(OKAY.code, "Ok")
    }
}
