package io.github.kotlinsmtp.protocol.command.api

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.AuthCommand
import io.github.kotlinsmtp.protocol.command.BdatCommand
import io.github.kotlinsmtp.protocol.command.DataCommand
import io.github.kotlinsmtp.protocol.command.EhloCommand
import io.github.kotlinsmtp.protocol.command.EtrnCommand
import io.github.kotlinsmtp.protocol.command.ExpnCommand
import io.github.kotlinsmtp.protocol.command.HeloCommand
import io.github.kotlinsmtp.protocol.command.HelpCommand
import io.github.kotlinsmtp.protocol.command.MailCommand
import io.github.kotlinsmtp.protocol.command.NoopCommand
import io.github.kotlinsmtp.protocol.command.QuitCommand
import io.github.kotlinsmtp.protocol.command.RcptCommand
import io.github.kotlinsmtp.protocol.command.RsetCommand
import io.github.kotlinsmtp.protocol.command.StartTlsCommand
import io.github.kotlinsmtp.protocol.command.VrfyCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.spi.SmtpMessageRejectedEvent
import io.github.kotlinsmtp.spi.SmtpMessageStage
import io.github.kotlinsmtp.spi.SmtpMessageTransferMode
import io.github.kotlinsmtp.utils.SmtpStatusCode.COMMAND_REJECTED
import io.github.kotlinsmtp.utils.SmtpStatusCode.BAD_COMMAND_SEQUENCE
import io.github.kotlinsmtp.utils.SmtpStatusCode.ERROR_IN_PROCESSING
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

internal enum class SmtpCommands(
    val instance: SmtpCommand
) {
    HELO(HeloCommand()),
    EHLO(EhloCommand()),
    AUTH(AuthCommand()),
    MAIL(MailCommand()),
    RCPT(RcptCommand()),
    DATA(DataCommand()),
    BDAT(BdatCommand()),
    ETRN(EtrnCommand()),
    RSET(RsetCommand()),
    VRFY(VrfyCommand()),
    EXPN(ExpnCommand()),
    HELP(HelpCommand()),
    NOOP(NoopCommand()),
    QUIT(QuitCommand()),
    STARTTLS(StartTlsCommand());

    companion object {
        suspend fun handle(rawCommand: String, session: SmtpSession) {
            val parsedCommand = ParsedCommand(rawCommand)

            try {
                // During CHUNKING (BDAT), BDAT consecutive chunk transmission is the protocol semantics,
                // allowing other commands may break state (practical compatibility).
                // - Allowed: BDAT / RSET / NOOP / QUIT / HELP
                if (session.isBdatInProgress()) {
                    val name = parsedCommand.commandName
                    val allowed = name == "BDAT" || name == "RSET" || name == "NOOP" || name == "QUIT" || name == "HELP"
                    if (!allowed) {
                        session.sendResponse(BAD_COMMAND_SEQUENCE.code, "BDAT in progress; send BDAT <size> [LAST] or RSET")
                        return
                    }
                }

                // Only EHLO/HELO allowed after STARTTLS (RFC 3207 compliance)
                if (session.requireEhloAfterTls) {
                    val name = parsedCommand.commandName
                    if (name == "EHLO" || name == "HELO") {
                        session.requireEhloAfterTls = false
                    } else {
                        session.sendResponse(BAD_COMMAND_SEQUENCE.code, "Must issue HELO/EHLO after STARTTLS")
                        return
                    }
                }

                val smtpCommand = entries.find { it.name == parsedCommand.commandName }

                if (smtpCommand != null) {
                    try {
                        smtpCommand.instance.execute(parsedCommand, session)
                    } catch (response: SmtpSendResponse) {
                        session.sendResponse(response.statusCode, response.message)

                        // DATA/BDAT transaction rejection is also exposed as a per-message event.
                        val transferMode = when (parsedCommand.commandName) {
                            "DATA" -> SmtpMessageTransferMode.DATA
                            "BDAT" -> SmtpMessageTransferMode.BDAT
                            else -> null
                        }
                        if (transferMode != null && session.server.hasEventHooks()) {
                            val context = session.buildSessionContext()
                            val envelope = session.buildMessageEnvelopeSnapshot()
                            session.server.notifyHooks { hook ->
                                hook.onMessageRejected(
                                    SmtpMessageRejectedEvent(
                                        context = context,
                                        envelope = envelope,
                                        transferMode = transferMode,
                                        stage = SmtpMessageStage.PROCESSING,
                                        responseCode = response.statusCode,
                                        responseMessage = response.message,
                                    )
                                )
                            }
                        }
                    }
                } else {
                    session.sendResponse(COMMAND_REJECTED.code, "Command unrecognized")
                }
            } catch (t: Throwable) {
                // Defense: Respond with 451 to prevent the session from being silently dropped by unexpected exceptions.
                log.error(t) { "Unhandled error while processing command='${parsedCommand.commandName}'" }
                session.sendResponse(ERROR_IN_PROCESSING.code, "Local error in processing")
                session.resetTransaction(preserveGreeting = true)
            }
        }
    }
}
