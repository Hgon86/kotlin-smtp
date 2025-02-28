package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.SERVICE_CLOSING_CHANNEL


class QuitCommand : SmtpCommand(
    "QUIT",
    "Closes the current session."
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        session.resetTransaction()
        session.sendResponse(SERVICE_CLOSING_CHANNEL.code, "Closing connection")
        session.shouldQuit = true
    }
}
