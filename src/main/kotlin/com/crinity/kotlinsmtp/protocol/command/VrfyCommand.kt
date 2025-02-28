package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.CANNOT_VERIFY_USER
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.COMMAND_NOT_IMPLEMENTED
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.INVALID_MAILBOX
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY


class VrfyCommand : SmtpCommand(
    "VRFY",
    "Checks if the given mailbox exists.",
    "searchTerm"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        val users = session.server.userHandler?.verify(command.rawWithoutCommand)

        if (users == null) {
            session.sendResponse(COMMAND_NOT_IMPLEMENTED.code, "The verify command is not supported")
        } else {
            when {
                users.isEmpty() ->
                    session.sendResponse(CANNOT_VERIFY_USER.code, "The given mailbox could not be verified")

                users.size == 1 ->
                    session.sendResponse(OKAY.code, users.first().stringRepresentation)

                else -> {
                    val response = buildList {
                        add(" Ambiguous; Possibilities are")
                        addAll(users.map { it.stringRepresentation })
                    }
                    session.sendMultilineResponse(INVALID_MAILBOX.code, response)
                }
            }
        }
    }
}
