package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY


class RsetCommand : SmtpCommand(
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
