package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.exception.SmtpSendResponse
import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.AddressUtils
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.INVALID_MAILBOX
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY
import com.crinity.kotlinsmtp.utils.isValidEmailAddress
import com.crinity.kotlinsmtp.utils.isValidEmailHost


class RcptCommand : SmtpCommand(
    "RCPT",
    "Specified a recipient who should receive the mail. This command can be called multiple times.",
    "TO:<(path:)address>"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (!command.rawCommand.startsWith("RCPT TO:", ignoreCase = true)) {
            respondSyntax()
        }

        if (command.parts.size < 2) {
            respondSyntax("Missing recipient information")
        }

        val toPart = command.parts[1].split(':')
        if (toPart.size < 2) {
            respondSyntax("Expected colon after TO")
        }

        val addressParts = AddressUtils.extractFromBrackets(toPart[1])
            ?.split(':') ?: respondSyntax("Missing or malformed address")

        var forwardPath: List<String>? = null
        val recipient = when (addressParts.size) {
            1 -> addressParts[0]
            2 -> {
                forwardPath = addressParts[0].split(',')
                addressParts[1]
            }

            else -> throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid recipient syntax")
        }

        if (forwardPath?.any { !it.isValidEmailHost() } == true) {
            throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid forward path")
        }

        if (!recipient.isValidEmailAddress()) {
            throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid email address")
        }

        session.transactionHandler?.to(recipient)
        session.sendResponse(OKAY.code, "Ok")
    }
}
