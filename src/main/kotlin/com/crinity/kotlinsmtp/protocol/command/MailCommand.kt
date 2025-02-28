package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.exception.SmtpSendResponse
import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.AddressUtils
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.INVALID_MAILBOX
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY
import com.crinity.kotlinsmtp.utils.isValidEmailAddress


class MailCommand : SmtpCommand(
    "MAIL",
    "Starts a mail transaction and specifies the sender.",
    "FROM:<senderAddress>"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (!command.rawCommand.startsWith("MAIL FROM:", ignoreCase = true)) {
            respondSyntax()
        }

        if (command.parts.size < 2) {
            respondSyntax("Missing sender information")
        }

        val parts = command.parts[1].split(':')
        if (parts.size < 2) {
            respondSyntax("Expected colon after FROM:")
        }

        val from = AddressUtils.extractFromBrackets(parts[1])
            ?: respondSyntax("Missing angle brackets around sender")

        if (!from.isValidEmailAddress()) {
            throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid email address")
        }

        session.transactionHandler?.from(from)
        session.sendResponse(OKAY.code, "Ok")
    }
}
