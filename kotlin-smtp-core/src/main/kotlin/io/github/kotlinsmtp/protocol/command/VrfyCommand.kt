package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.CANNOT_VERIFY_USER
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY


internal class VrfyCommand : SmtpCommand(
    "VRFY",
    "Checks if the given mailbox exists.",
    "searchTerm"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        // On internet-exposed SMTP servers, VRFY can be abused for user enumeration.
        // Therefore keep default behavior fixed at 252 and enable only via configuration.
        if (!session.server.enableVrfy || session.server.userHandler == null) {
            session.sendResponse(CANNOT_VERIFY_USER.code, "Cannot VRFY user, but will accept message and attempt delivery")
            return
        }

        val term = command.rawWithoutCommand.trim()
        if (term.isEmpty()) {
            respondSyntax("Empty VRFY argument not allowed.")
        }

        val users = runCatching { session.server.userHandler.verify(term) }.getOrDefault(emptyList())
        if (users.isEmpty()) {
            session.sendResponse(550, "5.1.1 User unknown")
            return
        }

        val lines = users.map { it.stringRepresentation }
        if (lines.size == 1) {
            session.sendResponse(OKAY.code, lines.first())
        } else {
            session.sendMultilineResponse(OKAY.code, lines)
        }
    }
}
