package com.crinity.kotlinsmtp.protocol.command

import com.crinity.kotlinsmtp.auth.AuthRegistry
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
        session.sessionData.greeted = true
        session.sessionData.usedEhlo = true

        // 응답 목록 생성 (서버가 실제 지원하는 기능만 광고)
        val responseLines = mutableListOf(
            session.server.hostname,
            // 구현상 입력은 순차 처리되며, 클라이언트가 응답을 기다리지 않고 명령을 연속 전송해도 정상 처리 가능
            "PIPELINING",
            // 8BITMIME: DATA 바이트를 ISO-8859-1로 1:1 보존하여 지원
            "8BITMIME",
            // SMTPUTF8: UTF-8 주소를 수용(최소 구현)
            "SMTPUTF8",
            // CHUNKING: BDAT로 메시지를 청크 단위로 전송 가능
            "CHUNKING",
            // BINARYMIME: CHUNKING과 함께 바이너리 본문 전송 허용
            "BINARYMIME",
            // DSN: RET/ENVID, NOTIFY/ORCPT 파라미터 수용(최소 반영)
            "DSN",
            "SIZE $MAX_MESSAGE_SIZE",
            "ENHANCEDSTATUSCODES",
        )

        // ETRN(관리 기능): 설정으로만 노출
        if (session.server.enableEtrn) {
            responseLines.add("ETRN")
        }

        // TLS 설정이 가능하고 아직 TLS가 아닌 경우에만 STARTTLS 광고 (ssl.enabled 연동)
        if (session.server.enableStartTls && session.server.sslContext != null && !session.isTls) {
            responseLines.add("STARTTLS")
        }

        // TLS 활성이고 인증 서비스가 켜져 있으면 AUTH PLAIN 광고
        if (session.server.enableAuth && session.isTls && (AuthRegistry.service?.enabled == true)) {
            // 실사용 호환: PLAIN + LOGIN 지원
            responseLines.add("AUTH PLAIN LOGIN")
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
