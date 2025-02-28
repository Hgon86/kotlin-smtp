package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.COMMAND_NOT_IMPLEMENTED


class ExpnCommand : SmtpCommand(
    "EXPN",
    "Checks if the given mailing list exists (and if so, this may return the membership)",
    "searchTerm"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        val searchTerm = command.rawWithoutCommand

        // TODO add implementation when user storage is added

        session.sendResponse(COMMAND_NOT_IMPLEMENTED.code, "The expand command is not supported")
    }
}
