package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SpoolTriggerResult
import io.github.kotlinsmtp.server.SmtpDomainSpooler
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.AddressUtils
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

        val queueDomain = normalizeQueueDomain(command.rawWithoutCommand)
            ?: respondSyntax("Invalid ETRN domain argument")

        val spooler = session.server.spooler
            ?: throw SmtpSendResponse(SmtpStatusCode.SERVICE_NOT_AVAILABLE.code, "Queue service not available")

        val (result, responseMessage) = runCatching {
            if (spooler is SmtpDomainSpooler) {
                val result = spooler.tryTriggerOnce(queueDomain)
                result to "Queue run triggered for $queueDomain"
            } else {
                // 도메인 미지원 스풀러는 기존 동작(전체 큐 트리거)으로 폴백합니다.
                val result = spooler.tryTriggerOnce()
                result to "Queue run triggered (domain filter not supported)"
            }
        }.getOrElse { t ->
            throw SmtpSendResponse(451, "4.3.0 Queue trigger failed: ${t.message ?: "unknown error"}")
        }

        when (result) {
            SpoolTriggerResult.ACCEPTED -> Unit
            SpoolTriggerResult.INVALID_ARGUMENT -> respondSyntax("Invalid ETRN domain argument")
            SpoolTriggerResult.UNAVAILABLE -> {
                throw SmtpSendResponse(
                    SmtpStatusCode.SERVICE_NOT_AVAILABLE.code,
                    "Queue service not available",
                )
            }
        }

        session.sendResponse(SmtpStatusCode.OKAY.code, responseMessage)
    }

    /**
     * ETRN 인자를 도메인 형태로 정규화합니다.
     *
     * @param rawArg 커맨드 원문에서 command 토큰을 제외한 인자 문자열
     * @return 정규화된 ASCII 도메인 또는 유효하지 않으면 null
     */
    private fun normalizeQueueDomain(rawArg: String): String? {
        val term = rawArg.trim()
        if (term.isEmpty()) return null
        val withoutPrefix = term.removePrefix("@").trim()
        if (withoutPrefix.isEmpty()) return null
        return AddressUtils.normalizeValidDomain(withoutPrefix)
    }
}
