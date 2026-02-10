package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.COMMAND_NOT_IMPLEMENTED
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY


internal class ExpnCommand : SmtpCommand(
    "EXPN",
    "Checks if the given mailing list exists (and if so, this may return the membership)",
    "searchTerm"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        // EXPN도 VRFY와 마찬가지로 정보 노출(메일링 리스트/멤버십 열거)에 악용될 수 있어,
        // 인터넷 노출 기본값은 비활성화로 두고, 설정+인증된 세션에서만 허용합니다.
        if (!session.server.enableExpn || session.server.mailingListHandler == null) {
            session.sendResponse(COMMAND_NOT_IMPLEMENTED.code, "The expand command is not supported")
            return
        }
        if (!session.sessionData.isAuthenticated) {
            throw SmtpSendResponse(530, "5.7.0 Authentication required")
        }

        val term = command.rawWithoutCommand.trim()
        if (term.isEmpty()) {
            respondSyntax("Empty EXPN argument not allowed.")
        }

        val members = runCatching { session.server.mailingListHandler.expand(term) }.getOrDefault(emptyList())
        if (members.isEmpty()) {
            session.sendResponse(550, "5.1.1 Mailing list not found")
            return
        }

        if (members.size == 1) {
            session.sendResponse(OKAY.code, members.first())
        } else {
            session.sendMultilineResponse(OKAY.code, members)
        }
    }
}
