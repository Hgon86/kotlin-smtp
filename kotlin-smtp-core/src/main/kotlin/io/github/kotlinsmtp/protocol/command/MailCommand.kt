package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.BAD_COMMAND_SEQUENCE
import io.github.kotlinsmtp.utils.SmtpStatusCode.INVALID_MAILBOX
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY
import io.github.kotlinsmtp.utils.SmtpStatusCode.EXCEEDED_STORAGE_ALLOCATION
import io.github.kotlinsmtp.utils.SmtpStatusCode.RECIPIENT_NOT_RECOGNIZED
import io.github.kotlinsmtp.utils.isValidEmailAddress
import io.github.kotlinsmtp.utils.AddressUtils
import io.github.kotlinsmtp.utils.Values.MAX_MESSAGE_SIZE
import io.github.kotlinsmtp.utils.parseMailArguments
import io.github.kotlinsmtp.utils.MailArguments

internal class MailCommand : SmtpCommand(
    "MAIL",
    "Starts a mail transaction and specifies the sender.",
    "FROM:<senderAddress> [SIZE=<size>] [BODY=7BIT]"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        // 상태/순서 검증: HELO/EHLO 이전에는 MAIL 금지
        if (!session.sessionData.greeted) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Send HELO/EHLO first")
        }

        // 설정상 인증이 필수인 경우: STARTTLS + AUTH 이후에만 MAIL 트랜잭션을 시작할 수 있게 합니다.
        // - 운영(MTA 수신)에서는 보통 required=false
        // - 제출(Submission) 용도로만 required=true 권장
        if (session.server.requireAuthForMail) {
            if (!session.isTls) {
                // TLS 없이 인증 강제는 보안상 의미가 없으므로 STARTTLS를 먼저 요구합니다.
                throw SmtpSendResponse(530, "5.7.0 Must issue STARTTLS first")
            }
            if (!session.sessionData.isAuthenticated) {
                throw SmtpSendResponse(530, "5.7.0 Authentication required")
            }
        }

        // 전체 원본 명령 문자열을 파서에 전달
        val esmtp = try {
            parseMailArguments(command.rawCommand)
        } catch (e: IllegalArgumentException) {
            respondSyntax(e.message ?: "Invalid MAIL FROM syntax")
        }

        // 빈 reverse-path (<>) 허용: DSN/bounce 메시지용
        val from = esmtp.address.ifBlank { null }

        // ESMTP 파라미터 검증
        validateMailParameters(esmtp)
        val smtpUtf8 = esmtp.parameters.containsKey("SMTPUTF8")

        if (from != null) {
            // SMTPUTF8 파라미터 없이 UTF-8 주소를 받으면 RFC 의미상 거부해야 합니다(기능 위주 최소 준수).
            val localPart = from.substringBeforeLast('@', "")
            if (!smtpUtf8 && !AddressUtils.isAllAscii(localPart)) {
                throw SmtpSendResponse(553, "5.6.7 SMTPUTF8 required")
            }
            val ok = if (smtpUtf8) AddressUtils.validateSmtpUtf8Address(from) else from.isValidEmailAddress()
            if (!ok) throw SmtpSendResponse(INVALID_MAILBOX.code, "Invalid email address")
        }

        // 상태 업데이트
        session.resetTransaction(preserveGreeting = true)
        session.sessionData.mailFrom = from ?: "" // 빈 reverse-path는 빈 문자열로 표기
        session.sessionData.mailParameters = esmtp.parameters
        session.sessionData.declaredSize = esmtp.parameters["SIZE"]?.toLongOrNull()
        session.sessionData.smtpUtf8 = smtpUtf8
        // RFC 3461(DSN) 관련 파라미터 저장
        session.sessionData.dsnRet = esmtp.parameters["RET"]?.uppercase()
        session.sessionData.dsnEnvid = esmtp.parameters["ENVID"]?.trim()

        // 트랜잭션 핸들러에 발신자 전달
        session.transactionHandler?.from(from ?: "")

        session.sendResponse(OKAY.code, "Ok")
    }

    /**
     * MAIL FROM 파라미터를 검증합니다.
     * 지원: SIZE, BODY=7BIT | BODY=8BITMIME
     * 미지원 파라미터는 555 오류로 거부
     */
    private fun validateMailParameters(esmtp: MailArguments) {
        esmtp.parameters.forEach { (key, value) ->
            when (key) {
                "SIZE" -> {
                    // SIZE 파라미터: 숫자 검증 및 최대 크기 확인
                    val numeric = value.toLongOrNull()
                        ?: throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "Invalid SIZE value"
                        )
                    if (numeric > MAX_MESSAGE_SIZE) {
                        throw SmtpSendResponse(
                            EXCEEDED_STORAGE_ALLOCATION.code,
                            "Message size exceeds limit ($MAX_MESSAGE_SIZE bytes)"
                        )
                    }
                }

                "BODY" -> {
                    // BODY 파라미터: 7BIT/8BITMIME/BINARYMIME 지원(실사용 기준)
                    // - BINARYMIME는 CHUNKING(BDAT) 경로로만 처리(아래 DATA에서 강제)
                    val normalized = value.uppercase()
                    if (normalized != "7BIT" && normalized != "8BITMIME" && normalized != "BINARYMIME") {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "BODY parameter must be 7BIT, 8BITMIME, or BINARYMIME"
                        )
                    }
                }

                // RFC 3461 (DSN)
                // - RET=FULL|HDRS
                // - ENVID=<id>
                // NOTE: 지금은 저장만 하고, 실제 DSN 생성 포맷(RFC 3464)은 TODO로 둡니다.
                "RET" -> {
                    val normalized = value.uppercase()
                    if (normalized != "FULL" && normalized != "HDRS") {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "RET parameter must be FULL or HDRS"
                        )
                    }
                }
                "ENVID" -> {
                    val trimmed = value.trim()
                    // ENVID는 공백이 없는 opaque identifier로 취급합니다(보수적).
                    if (trimmed.isEmpty() || trimmed.length > 100 || trimmed.any { it.isWhitespace() }) {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "Invalid ENVID value"
                        )
                    }
                }

                // RFC 6531 (SMTPUTF8)
                // - MAIL FROM에서 "SMTPUTF8" 플래그를 수용합니다.
                // - 값이 붙는 형태는 허용하지 않습니다(보수적으로 거부).
                "SMTPUTF8" -> {
                    if (value.isNotBlank()) {
                        throw SmtpSendResponse(
                            RECIPIENT_NOT_RECOGNIZED.code,
                            "SMTPUTF8 parameter must not have a value"
                        )
                    }
                }

                else -> {
                    // 미지원 파라미터는 명시적으로 거부
                    throw SmtpSendResponse(
                        RECIPIENT_NOT_RECOGNIZED.code,
                        "$key parameter not supported"
                    )
                }
            }
        }
    }
}
