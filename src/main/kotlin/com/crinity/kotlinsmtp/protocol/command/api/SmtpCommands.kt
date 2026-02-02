package com.crinity.kotlinsmtp.protocol.command.api

import com.crinity.kotlinsmtp.exception.SmtpSendResponse
import com.crinity.kotlinsmtp.protocol.command.AuthCommand
import com.crinity.kotlinsmtp.protocol.command.BdatCommand
import com.crinity.kotlinsmtp.protocol.command.DataCommand
import com.crinity.kotlinsmtp.protocol.command.EhloCommand
import com.crinity.kotlinsmtp.protocol.command.EtrnCommand
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
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.BAD_COMMAND_SEQUENCE
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.ERROR_IN_PROCESSING
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

enum class SmtpCommands(
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
                // CHUNKING(BDAT) 진행 중에는 BDAT 연속 청크 전송이 프로토콜 의미이므로,
                // 다른 커맨드를 허용하면 상태가 깨질 수 있습니다(실사용 호환).
                // - 허용: BDAT / RSET / NOOP / QUIT / HELP
                if (session.bdatDataChannel != null) {
                    val name = parsedCommand.commandName
                    val allowed = name == "BDAT" || name == "RSET" || name == "NOOP" || name == "QUIT" || name == "HELP"
                    if (!allowed) {
                        session.sendResponse(BAD_COMMAND_SEQUENCE.code, "BDAT in progress; send BDAT <size> [LAST] or RSET")
                        return
                    }
                }

                // STARTTLS 이후에는 EHLO/HELO만 허용 (RFC 3207 준수)
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
                    }
                } else {
                    session.sendResponse(COMMAND_REJECTED.code, "Command unrecognized")
                }
            } catch (t: Throwable) {
                // 방어: 예상치 못한 예외로 세션이 조용히 끊기지 않도록 451로 응답합니다.
                log.error(t) { "Unhandled error while processing command='${parsedCommand.commandName}'" }
                session.sendResponse(ERROR_IN_PROCESSING.code, "Local error in processing")
                session.resetTransaction(preserveGreeting = true)
            }
        }
    }
}