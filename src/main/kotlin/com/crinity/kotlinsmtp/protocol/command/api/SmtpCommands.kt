package com.crinity.kotlinsmtp.protocol.command.api

import com.crinity.kotlinsmtp.exception.SmtpSendResponse
import com.crinity.kotlinsmtp.protocol.command.DataCommand
import com.crinity.kotlinsmtp.protocol.command.EhloCommand
import com.crinity.kotlinsmtp.protocol.command.ExpnCommand
import com.crinity.kotlinsmtp.protocol.command.HeloCommand
import com.crinity.kotlinsmtp.protocol.command.HelpCommand
import com.crinity.kotlinsmtp.protocol.command.MailCommand
import com.crinity.kotlinsmtp.protocol.command.NoopCommand
import com.crinity.kotlinsmtp.protocol.command.QuitCommand
import com.crinity.kotlinsmtp.protocol.command.RcptCommand
import com.crinity.kotlinsmtp.protocol.command.RsetCommand
import com.crinity.kotlinsmtp.protocol.command.StartTlsCommand
import com.crinity.kotlinsmtp.protocol.command.VrfyCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.COMMAND_REJECTED

enum class SmtpCommands(
    val instance: SmtpCommand
) {
    HELO(HeloCommand()),
    EHLO(EhloCommand()),
    MAIL(MailCommand()),
    RCPT(RcptCommand()),
    DATA(DataCommand()),
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
            val smtpCommand = entries.find { it.name == parsedCommand.commandName }

            if (smtpCommand != null)
                try {
                    smtpCommand.instance.execute(parsedCommand, session)
                } catch (response: SmtpSendResponse) {
                    session.sendResponse(response.statusCode, response.message)
                }
            else {
                session.sendResponse(COMMAND_REJECTED.code, "Command unrecognized")
            }
        }
    }
}