package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.BAD_COMMAND_SEQUENCE
import io.github.kotlinsmtp.utils.SmtpStatusCode.INVALID_MAILBOX
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY
import io.github.kotlinsmtp.utils.SmtpStatusCode.INSUFFICIENT_STORAGE
import io.github.kotlinsmtp.utils.SmtpStatusCode.RECIPIENT_NOT_RECOGNIZED
import io.github.kotlinsmtp.utils.isValidEmailAddress
import io.github.kotlinsmtp.utils.isValidEmailHost
import io.github.kotlinsmtp.utils.AddressUtils
import io.github.kotlinsmtp.utils.Values.MAX_RECIPIENTS
import io.github.kotlinsmtp.utils.parseRcptArguments
import io.github.kotlinsmtp.model.RcptDsn

internal class RcptCommand : SmtpCommand(
    "RCPT",
    "Specified a recipient who should receive the mail. This command can be called multiple times.",
    "TO:<(path:)address> [parameters]"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        // 상태/순서 검증: MAIL FROM 이후에만 RCPT 허용
        if (session.sessionData.mailFrom == null) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Send MAIL FROM first")
        }

        // ESMTP 파라미터 파싱
        val esmtp = try {
            parseRcptArguments(command.rawCommand)
        } catch (e: IllegalArgumentException) {
            respondSyntax(e.message ?: "Invalid RCPT TO syntax")
        }

        // RFC 3461(DSN) 파라미터 최소 검증(실사용 기준)
        validateRcptDsnParameters(esmtp.parameters)

        // forward-path 파싱: <@host1,@host2:user@final> 형식 지원
        val addressParts = esmtp.address.split(':')
        var forwardPath: List<String>? = null
        val recipient = when (addressParts.size) {
            1 -> addressParts[0] // 단순 주소: user@domain
            2 -> {
                // forward-path 포함: @host1,@host2:user@final
                forwardPath = addressParts[0].split(',')
                addressParts[1]
            }
            else -> throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid recipient syntax")
        }

        // forward-path 검증 (있는 경우)
        if (forwardPath?.any { !it.isValidEmailHost() } == true) {
            throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid forward path")
        }

        // 최종 수신자 주소 검증
        val recipientLocalPart = recipient.substringBeforeLast('@', "")
        if (!session.sessionData.smtpUtf8 && !AddressUtils.isAllAscii(recipientLocalPart)) {
            throw SmtpSendResponse(553, "5.6.7 SMTPUTF8 required")
        }
        val ok = if (session.sessionData.smtpUtf8) AddressUtils.validateSmtpUtf8Address(recipient) else recipient.isValidEmailAddress()
        if (!ok) {
            throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid email address")
        }

        // 수신자 상한 검증
        if (session.sessionData.recipientCount >= MAX_RECIPIENTS) {
            throw SmtpSendResponse(INSUFFICIENT_STORAGE.code, "Too many recipients")
        }

        // 상태 업데이트
        session.sessionData.recipientCount += 1
        session.envelopeRecipients.add(recipient)
        // DSN 옵션 저장(수신자별)
        val notify = esmtp.parameters["NOTIFY"]?.trim()?.takeIf { it.isNotBlank() }
        val orcpt = esmtp.parameters["ORCPT"]?.trim()?.takeIf { it.isNotBlank() }
        if (notify != null || orcpt != null) {
            session.sessionData.rcptDsn[recipient] = RcptDsn(notify = notify, orcpt = orcpt)
        }

        // 트랜잭션 핸들러에 최종 수신자 전달
        session.transactionHandler?.to(recipient)
        session.sendResponse(OKAY.code, "Ok")
    }

    private fun validateRcptDsnParameters(parameters: Map<String, String>) {
        // NOTIFY=NEVER | (SUCCESS,FAILURE,DELAY 조합)
        parameters["NOTIFY"]?.let { raw ->
            val value = raw.trim()
            if (value.isEmpty()) respondSyntax("Invalid NOTIFY value")
            val tokens = value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) respondSyntax("Invalid NOTIFY value")
            if ("NEVER" in tokens && tokens.size != 1) respondSyntax("NOTIFY=NEVER must not be combined")
            val allowed = setOf("SUCCESS", "FAILURE", "DELAY", "NEVER")
            if (tokens.any { it !in allowed }) respondSyntax("Invalid NOTIFY value")
        }

        // ORCPT=rfc822;addr 형태를 보수적으로 검증합니다.
        parameters["ORCPT"]?.let { raw ->
            val value = raw.trim()
            if (!value.startsWith("rfc822;", ignoreCase = true)) {
                respondSyntax("ORCPT must start with rfc822;")
            }
            val addr = value.substringAfter(';', "").trim()
            if (addr.isEmpty() || addr.length > 512) {
                respondSyntax("Invalid ORCPT value")
            }
        }

        // 그 외 미지원 RCPT 파라미터는 거부(실사용 기준에서 안전).
        val supported = setOf("NOTIFY", "ORCPT")
        for (key in parameters.keys) {
            if (key !in supported) {
                throw SmtpSendResponse(
                    RECIPIENT_NOT_RECOGNIZED.code,
                    "$key parameter not supported"
                )
            }
        }
    }
}
