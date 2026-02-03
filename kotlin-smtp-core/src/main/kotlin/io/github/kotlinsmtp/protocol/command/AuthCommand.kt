package io.github.kotlinsmtp.protocol.command

import io.github.kotlinsmtp.auth.SaslPlain
import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.protocol.command.api.ParsedCommand
import io.github.kotlinsmtp.protocol.command.api.SmtpCommand
import io.github.kotlinsmtp.server.SmtpSession
import io.github.kotlinsmtp.utils.SmtpStatusCode.BAD_COMMAND_SEQUENCE
import io.github.kotlinsmtp.utils.SmtpStatusCode.COMMAND_NOT_IMPLEMENTED
import io.github.kotlinsmtp.utils.SmtpStatusCode.OKAY
import java.time.Instant
import java.util.Base64
import kotlin.math.min
import kotlin.math.pow

internal class AuthCommand : SmtpCommand(
    "AUTH",
    "Authenticate to the server (PLAIN, LOGIN)",
    "PLAIN [initial-response] | LOGIN [initial-response]"
) {
    override suspend fun execute(command: ParsedCommand, session: SmtpSession) {
        val authService = session.server.authService
        if (authService == null || !authService.enabled) {
            throw SmtpSendResponse(COMMAND_NOT_IMPLEMENTED.code, "AUTH not supported")
        }

        if (!session.server.enableAuth) {
            throw SmtpSendResponse(COMMAND_NOT_IMPLEMENTED.code, "AUTH not supported on this service")
        }

        // 실사용 서버 관례: HELO/EHLO 이후에만 AUTH 허용
        // (일부 클라이언트는 서버 배너 직후 AUTH를 시도할 수 있으므로 명확히 503으로 거부)
        if (!session.sessionData.greeted) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Send HELO/EHLO first")
        }

        // 이미 인증된 상태라면 재인증을 허용하지 않습니다(세션 안정성).
        if (session.sessionData.isAuthenticated) {
            throw SmtpSendResponse(503, "5.5.1 Already authenticated")
        }

        // TLS 이후에만 허용
        if (!session.isTls) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Must issue STARTTLS first")
        }

        // 잠금 상태 확인 (세션 스코프)
        val now = Instant.now().toEpochMilli()
        val lockedUntil = session.sessionData.authLockedUntilEpochMs
        if (lockedUntil != null && now < lockedUntil) {
            val waitSec = ((lockedUntil - now) / 1000).coerceAtLeast(1)
            // 454(일시 실패)로 응답해 클라이언트가 재시도할 수 있게 합니다.
            throw SmtpSendResponse(454, "4.7.0 Temporary authentication lock. Try again in ${waitSec}s")
        }

        val parts = command.parts
        if (parts.size < 2) {
            respondSyntax("Missing SASL mechanism")
        }

        val mechanism = parts[1].uppercase()

        val (username, password) = when (mechanism) {
            "PLAIN" -> {
                // AUTH PLAIN은 다음 두 가지를 모두 지원합니다.
                // 1) AUTH PLAIN <initial-response>
                // 2) AUTH PLAIN            (334 후 다음 라인에서 응답 수신)
                val initialResponse = parts.getOrNull(2)?.trim()
                val responseBase64 = if (initialResponse != null) {
                    // RFC 4954: empty initial-response는 "="로 표현될 수 있습니다.
                    if (initialResponse == "=") "" else initialResponse
                } else {
                    // 334는 SmtpStatusCode에 없으므로 raw 응답으로 보냅니다.
                    session.respondLine("334 ")
                    val next = session.readLine()
                        ?: throw SmtpSendResponse(451, "4.3.0 Authentication aborted")
                    if (next == "*") {
                        throw SmtpSendResponse(501, "5.7.0 Authentication cancelled")
                    }
                    next.trim()
                }

                val creds = SaslPlain.decode(responseBase64)
                    ?: throw SmtpSendResponse(501, "5.5.2 Invalid SASL PLAIN response")
                creds.authcid to creds.password
            }

            "LOGIN" -> {
                // AUTH LOGIN 흐름(실사용 클라이언트 호환을 위해 구현)
                // 1) AUTH LOGIN [initial-response(=username)]
                // 2) 334 <base64("Username:")> → username
                // 3) 334 <base64("Password:")> → password
                val initial = parts.getOrNull(2)?.trim()
                val userB64 = initial?.let { if (it == "=") "" else it } ?: run {
                    session.respondLine("334 VXNlcm5hbWU6") // "Username:"
                    val next = session.readLine()
                        ?: throw SmtpSendResponse(451, "4.3.0 Authentication aborted")
                    if (next == "*") throw SmtpSendResponse(501, "5.7.0 Authentication cancelled")
                    next.trim()
                }
                val user = decodeBase64Utf8(userB64)
                    ?: throw SmtpSendResponse(501, "5.5.2 Invalid AUTH LOGIN username")
                if (user.isBlank()) throw SmtpSendResponse(501, "5.5.2 Invalid AUTH LOGIN username")

                session.respondLine("334 UGFzc3dvcmQ6") // "Password:"
                val passB64 = session.readLine()
                    ?: throw SmtpSendResponse(451, "4.3.0 Authentication aborted")
                if (passB64 == "*") throw SmtpSendResponse(501, "5.7.0 Authentication cancelled")
                val pass = decodeBase64Utf8(passB64.trim())
                    ?: throw SmtpSendResponse(501, "5.5.2 Invalid AUTH LOGIN password")
                if (pass.isBlank()) throw SmtpSendResponse(501, "5.5.2 Invalid AUTH LOGIN password")

                user to pass
            }

            else -> {
                throw SmtpSendResponse(504, "5.5.4 Unrecognized authentication type")
            }
        }

        // 공유 Rate Limiter 확인 (재접속 우회 방지)
        val clientIp = extractClientIp(session.sessionData.peerAddress)
        session.server.authRateLimiter?.let { limiter ->
            val sharedLockSec = limiter.checkLock(clientIp, username)
            if (sharedLockSec != null) {
                throw SmtpSendResponse(454, "4.7.0 Temporary authentication lock. Try again in ${sharedLockSec}s")
            }
        }

        val ok = authService.verify(username, password)
        if (!ok) {
            // 세션 스코프 잠금
            val attempts = (session.sessionData.authFailedAttempts ?: 0) + 1
            session.sessionData.authFailedAttempts = attempts
            // 지수 백오프: min(10분, 2^(attempt-1) * 5초)
            val backoffSec = min(600.0, 5.0 * 2.0.pow((attempts - 1).toDouble())).toLong()
            session.sessionData.authLockedUntilEpochMs = Instant.now().plusSeconds(backoffSec).toEpochMilli()

            // 공유 Rate Limiter에 실패 기록
            session.server.authRateLimiter?.recordFailure(clientIp, username)

            // 535(자격 증명 오류) + 강화코드(5.7.8)로 명확히 표현합니다.
            throw SmtpSendResponse(535, "5.7.8 Authentication credentials invalid")
        }

        // 성공: 실패 카운터/락 해제 및 공유 Rate Limiter 기록 초기화
        session.sessionData.authFailedAttempts = 0
        session.sessionData.authLockedUntilEpochMs = null
        session.server.authRateLimiter?.recordSuccess(clientIp, username)
        session.sessionData.isAuthenticated = true
        session.sendResponse(OKAY.code, "Authentication successful")
    }

    private fun decodeBase64Utf8(input: String): String? = runCatching {
        val decoded = Base64.getDecoder().decode(input.trim())
        String(decoded, Charsets.UTF_8)
    }.getOrNull()

    /**
     * peerAddress에서 클라이언트 IP 추출
     * 형식: "hostname [1.2.3.4]:port" 또는 "1.2.3.4:port"
     */
    private fun extractClientIp(peerAddress: String?): String? {
        if (peerAddress == null) return null

        // 대괄호 안의 IP 추출
        val bracketMatch = Regex("\\[([^\\]]+)\\]").find(peerAddress)
        if (bracketMatch != null) {
            return bracketMatch.groupValues[1]
        }

        // 콜론 앞의 IP 추출
        return peerAddress.substringBefore(':').trim()
    }
}
