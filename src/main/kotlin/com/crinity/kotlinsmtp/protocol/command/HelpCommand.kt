package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommands
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.HELP_MESSAGE


class HelpCommand : SmtpCommand(
    "HELP",
    "Displays helpful information about the server in general, or about the given (optional) search term.",
    "(searchTerm)"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        val searchTerm = command.rawWithoutCommand

        if (searchTerm.isNotEmpty()) {
            val searchedCommand = SmtpCommands.entries.find { it.name == searchTerm.uppercase() }
            if (searchedCommand != null) {
                session.sendResponse(HELP_MESSAGE.code, searchedCommand.instance.description)
            } else {
                session.sendResponse(HELP_MESSAGE.code, "The given searchTerm / command was not found.")
            }
        } else {
            session.sendResponse(
                HELP_MESSAGE.code,
                "Issue 'HELP searchTerm' to get more information about a specific command."
            )
        }
    }
}
