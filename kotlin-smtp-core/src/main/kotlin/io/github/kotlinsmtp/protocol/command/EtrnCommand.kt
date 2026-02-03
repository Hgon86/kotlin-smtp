package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode

/**
 * ETRN (RFC 1985)
 *
 * 프로덕션에서 흔한 커맨드는 아니지만, 운영/관리 시나리오에서 "큐(스풀) 즉시 처리" 트리거로 유용합니다.
 *
 * - 인터넷 노출 기본값은 비활성화(enableEtrn=false)
 * - 활성화 시에도 남용 방지를 위해 인증된 세션에서만 허용합니다.
 */
internal class EtrnCommand : SmtpCommand(
    "ETRN",
    "Requests that the server attempt to process the queue for the given domain (admin/management).",
    "<domain>"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        if (!session.server.enableEtrn) {
            throw SmtpSendResponse(SmtpStatusCode.COMMAND_NOT_IMPLEMENTED.code, "ETRN not supported")
        }
        if (!session.sessionData.isAuthenticated) {
            throw SmtpSendResponse(530, "5.7.0 Authentication required")
        }

        // 기능 우선: 도메인 인자 유무와 관계없이 현재 스풀 큐를 한 번 처리하도록 트리거합니다.
        // TODO: <domain> 기반 필터링/정책(관리망/권한) 강화
        session.server.spooler?.triggerOnce()
            ?: throw SmtpSendResponse(SmtpStatusCode.SERVICE_NOT_AVAILABLE.code, "Queue service not available")

        session.sendResponse(SmtpStatusCode.OKAY.code, "Queue run triggered")
    }
}
