package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.INVALID_MAILBOX
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY
import io.github.kotlinsmtp.utils.SmtpStatusCode.INSUFFICIENT_STORAGE
import io.github.kotlinsmtp.utils.SmtpStatusCode.RECIPIENT_NOT_RECOGNIZED
import io.github.kotlinsmtp.utils.isValidEmailAddress
import io.github.kotlinsmtp.utils.isValidEmailHost
import io.github.kotlinsmtp.utils.AddressUtils
import io.github.kotlinsmtp.utils.Values.MAX_RECIPIENTS
import io.github.kotlinsmtp.utils.parseRcptArguments
import io.github.kotlinsmtp.model.RcptDsn

internal class RcptCommand : SmtpCommand(
    "RCPT",
    "Specified a recipient who should receive the mail. This command can be called multiple times.",
    "TO:<(path:)address> [parameters]"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        // Parse ESMTP parameters
        val esmtp = try {
            parseRcptArguments(command.rawCommand)
        } catch (e: IllegalArgumentException) {
            respondSyntax(e.message ?: "Invalid RCPT TO syntax")
        }

        // Minimal validation for RFC 3461 (DSN) parameters (practical baseline)
        validateRcptDsnParameters(esmtp.parameters)

        // Parse forward-path: support <@host1,@host2:user@final> format
        val addressParts = esmtp.address.split(':')
        var forwardPath: List<String>? = null
        val recipient = when (addressParts.size) {
            1 -> addressParts[0] // Simple address: user@domain
            2 -> {
                // Includes forward-path: @host1,@host2:user@final
                forwardPath = addressParts[0].split(',')
                addressParts[1]
            }
            else -> throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid recipient syntax")
        }

        // Validate forward-path (if present)
        if (forwardPath?.any { !it.isValidEmailHost() } == true) {
            throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid forward path")
        }

        // Validate final recipient address
        val recipientLocalPart = recipient.substringBeforeLast('@', "")
        if (!session.sessionData.smtpUtf8 && !AddressUtils.isAllAscii(recipientLocalPart)) {
            throw SmtpSendResponse(553, "5.6.7 SMTPUTF8 required")
        }
        val ok = if (session.sessionData.smtpUtf8) AddressUtils.validateSmtpUtf8Address(recipient) else recipient.isValidEmailAddress()
        if (!ok) {
            throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid email address")
        }

        // Validate recipient upper limit
        if (session.sessionData.recipientCount >= MAX_RECIPIENTS) {
            throw SmtpSendResponse(INSUFFICIENT_STORAGE.code, "Too many recipients")
        }

        // Update state
        session.sessionData.recipientCount += 1
        session.envelopeRecipients.add(recipient)
        // Store DSN options (per recipient)
        val notify = esmtp.parameters["NOTIFY"]?.trim()?.takeIf { it.isNotBlank() }
        val orcpt = esmtp.parameters["ORCPT"]?.trim()?.takeIf { it.isNotBlank() }
        if (notify != null || orcpt != null) {
            session.sessionData.rcptDsn[recipient] = RcptDsn(notify = notify, orcpt = orcpt)
        }

        // Pass final recipient to transaction processor
        session.transactionProcessor?.to(recipient)
        session.sendResponse(OKAY.code, "Ok")
    }

    private fun validateRcptDsnParameters(parameters: Map<String, String>) {
        // NOTIFY=NEVER | (combination of SUCCESS,FAILURE,DELAY)
        parameters["NOTIFY"]?.let { raw ->
            val value = raw.trim()
            if (value.isEmpty()) respondSyntax("Invalid NOTIFY value")
            val tokens = value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) respondSyntax("Invalid NOTIFY value")
            if ("NEVER" in tokens && tokens.size != 1) respondSyntax("NOTIFY=NEVER must not be combined")
            val allowed = setOf("SUCCESS", "FAILURE", "DELAY", "NEVER")
            if (tokens.any { it !in allowed }) respondSyntax("Invalid NOTIFY value")
        }

        // Conservatively validate ORCPT=rfc822;addr format.
        parameters["ORCPT"]?.let { raw ->
            val value = raw.trim()
            if (!value.startsWith("rfc822;", ignoreCase = true)) {
                respondSyntax("ORCPT must start with rfc822;")
            }
            val addr = value.substringAfter(';', "").trim()
            if (addr.isEmpty() || addr.length > 512) {
                respondSyntax("Invalid ORCPT value")
            }
        }

        // Reject other unsupported RCPT parameters (safe for practical baseline).
        val supported = setOf("NOTIFY", "ORCPT")
        for (key in parameters.keys) {
            if (key !in supported) {
                throw SmtpSendResponse(
                    RECIPIENT_NOT_RECOGNIZED.code,
                    "$key parameter not supported"
                )
            }
        }
    }
}
