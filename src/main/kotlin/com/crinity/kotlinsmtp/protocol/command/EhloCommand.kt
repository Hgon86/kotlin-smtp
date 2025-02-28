package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.protocol.command.api.ParsedCommand
import com.crinity.kotlinsmtp.protocol.command.api.SmtpCommand
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode.OKAY
import com.crinity.kotlinsmtp.utils.Values.MAX_MESSAGE_SIZE


class EhloCommand : SmtpCommand(
    "EHLO",
    "Extended HELO - The client introduces itself.",
    "<domain>"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (command.parts.size < 2) {
            respondSyntax("Empty HELO/EHLO argument not allowed.")
        }

        // 세션 데이터 초기화 및 식별자 저장
        session.resetTransaction()
        session.sessionData.helo = command.parts[1]

        // 응답 목록 생성
        val responseLines = mutableListOf(
            session.server.hostname,
            "8BITMIME",
            "SIZE $MAX_MESSAGE_SIZE",
            "ENHANCEDSTATUSCODES",
            "SMTPUTF8"
        )

        // TLS가 설정되어 있고 아직 TLS 연결이 아닌 경우 STARTTLS 추가
        if (session.server.sslContext != null && !session.isTls) {
            responseLines.add("STARTTLS")
        }

        // 확장 코드 없이 응답 전송
        val code = OKAY.code
        responseLines.forEachIndexed { index, line ->
            if (index != responseLines.lastIndex)
                session.respondLine("$code-$line")
            else
                session.respondLine("$code $line")
        }
    }
}
