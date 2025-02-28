package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY


class NoopCommand : SmtpCommand(
    "NOOP",
    "This command will lead to no operations being issued."
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        session.sendResponse(OKAY.code, "Ok")
    }
}
