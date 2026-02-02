package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommands
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.HELP_MESSAGE


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
