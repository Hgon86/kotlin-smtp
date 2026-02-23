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

        // Practical server convention: allow AUTH only after HELO/EHLO.
        // (Some clients may try AUTH right after server banner, so explicitly reject with 503.)
        if (!session.sessionData.greeted) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Send HELO/EHLO first")
        }

        // Do not allow re-authentication when already authenticated (session stability).
        if (session.sessionData.isAuthenticated) {
            throw SmtpSendResponse(503, "5.5.1 Already authenticated")
        }

        // Allow only after TLS
        if (!session.isTls) {
            throw SmtpSendResponse(BAD_COMMAND_SEQUENCE.code, "Must issue STARTTLS first")
        }

        // Check lock status (session scope)
        val now = Instant.now().toEpochMilli()
        val lockedUntil = session.sessionData.authLockedUntilEpochMs
        if (lockedUntil != null && now < lockedUntil) {
            val waitSec = ((lockedUntil - now) / 1000).coerceAtLeast(1)
            // Respond with 454 (temporary failure) so client can retry.
            throw SmtpSendResponse(454, "4.7.0 Temporary authentication lock. Try again in ${waitSec}s")
        }

        val parts = command.parts
        if (parts.size < 2) {
            respondSyntax("Missing SASL mechanism")
        }

        val mechanism = parts[1].uppercase()

        val (username, password) = when (mechanism) {
            "PLAIN" -> {
                // AUTH PLAIN supports both of the following.
                // 1) AUTH PLAIN <initial-response>
                // 2) AUTH PLAIN            (receive response in next line after 334)
                val initialResponse = parts.getOrNull(2)?.trim()
                val responseBase64 = if (initialResponse != null) {
                    // RFC 4954: empty initial-response can be represented as "=".
                    if (initialResponse == "=") "" else initialResponse
                } else {
                    // 334 is not in SmtpStatusCode, so send as raw response.
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
                // AUTH LOGIN flow (implemented for real-world client compatibility)
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

        // Check shared Rate Limiter (prevent reconnection bypass)
        val clientIp = session.clientIpAddress()
        session.server.authRateLimiter?.let { limiter ->
            val sharedLockSec = limiter.checkLock(clientIp, username)
            if (sharedLockSec != null) {
                throw SmtpSendResponse(454, "4.7.0 Temporary authentication lock. Try again in ${sharedLockSec}s")
            }
        }

        val ok = authService.verify(username, password)
        if (!ok) {
            // Session scope lock
            val attempts = (session.sessionData.authFailedAttempts ?: 0) + 1
            session.sessionData.authFailedAttempts = attempts
            // Exponential backoff: min(10 minutes, 2^(attempt-1) * 5 seconds)
            val backoffSec = min(600.0, 5.0 * 2.0.pow((attempts - 1).toDouble())).toLong()
            session.sessionData.authLockedUntilEpochMs = Instant.now().plusSeconds(backoffSec).toEpochMilli()

            // Record failure in shared Rate Limiter
            session.server.authRateLimiter?.recordFailure(clientIp, username)

            // Clearly express with 535 (credential error) + enhanced code (5.7.8).
            throw SmtpSendResponse(535, "5.7.8 Authentication credentials invalid")
        }

        // Success: reset failure counter/lock and clear shared Rate Limiter record
        session.sessionData.authFailedAttempts = 0
        session.sessionData.authLockedUntilEpochMs = null
        session.server.authRateLimiter?.recordSuccess(clientIp, username)
        session.sessionData.isAuthenticated = true
        session.sessionData.authenticatedUsername = username
        session.sendResponse(OKAY.code, "Authentication successful")
    }

    private fun decodeBase64Utf8(input: String): String? = runCatching {
        val decoded = Base64.getDecoder().decode(input.trim())
        String(decoded, Charsets.UTF_8)
    }.getOrNull()

}
