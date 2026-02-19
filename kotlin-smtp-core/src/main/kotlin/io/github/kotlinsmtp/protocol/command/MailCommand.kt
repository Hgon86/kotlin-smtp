package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.BAD_COMMAND_SEQUENCE
import io.github.kotlinsmtp.utils.SmtpStatusCode.INVALID_MAILBOX
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY
import io.github.kotlinsmtp.utils.SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION
import io.github.kotlinsmtp.utils.SmtpStatusCode.RECIPIENT_NOT_RECOGNIZED
import io.github.kotlinsmtp.utils.isValidEmailAddress
import io.github.kotlinsmtp.utils.AddressUtils
import io.github.kotlinsmtp.utils.Values.MAX_MESSAGE_SIZE
import io.github.kotlinsmtp.utils.parseMailArguments
import io.github.kotlinsmtp.utils.MailArguments

internal class MailCommand : SmtpCommand(
    "MAIL",
    "Starts a mail transaction and specifies the sender.",
    "FROM:<senderAddress> [SIZE=<size>] [BODY=7BIT]"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        // State/sequence validation: MAIL is not allowed before HELO/EHLO.
        if (!session.sessionData.greeted) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Send HELO/EHLO first")
        }

        // If authentication is required by config, allow MAIL transaction only after STARTTLS + AUTH.
        // - In operations (MTA receiving), required=false is typical.
        // - required=true is recommended only for submission use.
        if (session.server.requireAuthForMail) {
            if (!session.isTls) {
                // Enforcing auth without TLS is not meaningful for security, so require STARTTLS first.
                throw SmtpSendResponse(530, "5.7.0 Must issue STARTTLS first")
            }
            if (!session.sessionData.isAuthenticated) {
                throw SmtpSendResponse(530, "5.7.0 Authentication required")
            }
        }

        // Pass full raw command string to parser
        val esmtp = try {
            parseMailArguments(command.rawCommand)
        } catch (e: IllegalArgumentException) {
            respondSyntax(e.message ?: "Invalid MAIL FROM syntax")
        }

        // Allow empty reverse-path (<>): for DSN/bounce messages
        val from = esmtp.address.ifBlank { null }

        // Validate ESMTP parameters
        validateMailParameters(esmtp)
        val smtpUtf8 = esmtp.parameters.containsKey("SMTPUTF8")

        if (from != null) {
            // Receiving UTF-8 address without SMTPUTF8 parameter should be rejected per RFC semantics (minimum practical compliance).
            val localPart = from.substringBeforeLast('@', "")
            if (!smtpUtf8 && !AddressUtils.isAllAscii(localPart)) {
                throw SmtpSendResponse(553, "5.6.7 SMTPUTF8 required")
            }
            val ok = if (smtpUtf8) AddressUtils.validateSmtpUtf8Address(from) else from.isValidEmailAddress()
            if (!ok) throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid email address")
        }

        // Update state
        session.resetTransaction(preserveGreeting = true)
        session.sessionData.mailFrom = from ?: "" // Represent empty reverse-path as empty string
        session.sessionData.mailParameters = esmtp.parameters
        session.sessionData.declaredSize = esmtp.parameters["SIZE"]?.toLongOrNull()
        session.sessionData.smtpUtf8 = smtpUtf8
        // Store RFC 3461 (DSN) related parameters
        session.sessionData.dsnRet = esmtp.parameters["RET"]?.uppercase()
        session.sessionData.dsnEnvid = esmtp.parameters["ENVID"]?.trim()

        // Pass sender to transaction handler
        session.transactionHandler?.from(from ?: "")

        session.sendResponse(OKAY.code, "Ok")
    }

    /**
     * Validate MAIL FROM parameters.
     * Supported: SIZE, BODY=7BIT | BODY=8BITMIME
     * Unsupported parameters are rejected with 555
     */
    private fun validateMailParameters(esmtp: MailArguments) {
        esmtp.parameters.forEach { (key, value) ->
            when (key) {
                "SIZE" -> {
                    // SIZE parameter: validate numeric value and max size
                    val numeric = value.toLongOrNull()
                        ?: throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "Invalid SIZE value"
                        )
                    if (numeric > MAX_MESSAGE_SIZE) {
                        throw SmtpSendResponse(
                            EXCEEDED_STORAGE_ALLOCATION.code,
                            "Message size exceeds limit ($MAX_MESSAGE_SIZE bytes)"
                        )
                    }
                }

                "BODY" -> {
                    // BODY parameter: support 7BIT/8BITMIME/BINARYMIME (practical baseline)
                    // - BINARYMIME is handled only through CHUNKING(BDAT) path (enforced in DATA below)
                    val normalized = value.uppercase()
                    if (normalized != "7BIT" && normalized != "8BITMIME" && normalized != "BINARYMIME") {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "BODY parameter must be 7BIT, 8BITMIME, or BINARYMIME"
                        )
                    }
                }

                // RFC 3461 (DSN)
                // - RET=FULL|HDRS
                // - ENVID=<id>
                // NOTE: currently only stored; actual DSN generation format (RFC 3464) remains TODO.
                "RET" -> {
                    val normalized = value.uppercase()
                    if (normalized != "FULL" && normalized != "HDRS") {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "RET parameter must be FULL or HDRS"
                        )
                    }
                }
                "ENVID" -> {
                    val trimmed = value.trim()
                    // Treat ENVID as whitespace-free opaque identifier (conservative).
                    if (trimmed.isEmpty() || trimmed.length > 100 || trimmed.any { it.isWhitespace() }) {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "Invalid ENVID value"
                        )
                    }
                }

                // RFC 6531 (SMTPUTF8)
                // - Accept "SMTPUTF8" flag at MAIL FROM.
                // - Form with value is not allowed (conservative rejection).
                "SMTPUTF8" -> {
                    if (value.isNotBlank()) {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "SMTPUTF8 parameter must not have a value"
                        )
                    }
                }

                else -> {
                    // Explicitly reject unsupported parameters
                    throw SmtpSendResponse(
                        RECIPIENT_NOT_RECOGNIZED.code,
                        "$key parameter not supported"
                    )
                }
            }
        }
    }
}
