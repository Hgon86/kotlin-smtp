package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.SERVICE_CLOSING_CHANNEL


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
