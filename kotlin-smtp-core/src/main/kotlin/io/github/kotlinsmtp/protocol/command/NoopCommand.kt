package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY


class NoopCommand : SmtpCommand(
    "NOOP",
    "This command will lead to no operations being issued."
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        session.sendResponse(OKAY.code, "Ok")
    }
}
