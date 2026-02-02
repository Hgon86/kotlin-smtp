package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.CANNOT_VERIFY_USER
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY


class VrfyCommand : SmtpCommand(
    "VRFY",
    "Checks if the given mailbox exists.",
    "searchTerm"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        // 인터넷 노출 SMTP 서버에서는 VRFY가 사용자 열거(User Enumeration)로 악용되기 쉽습니다.
        // 따라서 기본값은 252로 고정하고, 설정으로만 활성화합니다.
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
