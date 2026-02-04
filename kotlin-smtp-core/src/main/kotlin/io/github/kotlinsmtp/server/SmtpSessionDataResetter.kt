package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData

/**
 * 트랜잭션 리셋 시 SessionData를 재구성합니다.
 *
 * - STARTTLS 이후 등에서 인증/세션 상태 리셋 요구사항을 반영합니다.
 * - 보안 강화를 위해 ESMTP/DSN 파라미터는 트랜잭션 단위로 초기화합니다.
 */
internal object SmtpSessionDataResetter {

    fun reset(
        current: SessionData,
        preserveGreeting: Boolean,
        preserveAuth: Boolean,
        serverHostname: String,
        peerAddress: String?,
        tlsActive: Boolean,
    ): SessionData {
        val oldHello = if (preserveGreeting) current.helo else null
        val greeted = if (preserveGreeting) current.greeted else false
        val usedEhlo = if (preserveGreeting) current.usedEhlo else false

        val authenticated = if (preserveAuth) current.isAuthenticated else false
        val authFailedAttempts = if (preserveAuth) current.authFailedAttempts else null
        val authLockedUntilEpochMs = if (preserveAuth) current.authLockedUntilEpochMs else null

        return SessionData().also {
            it.helo = oldHello
            it.greeted = greeted
            it.usedEhlo = usedEhlo
            it.serverHostname = serverHostname
            it.peerAddress = peerAddress
            it.tlsActive = tlsActive

            it.isAuthenticated = authenticated
            it.authFailedAttempts = authFailedAttempts
            it.authLockedUntilEpochMs = authLockedUntilEpochMs

            it.mailParameters = emptyMap()
            it.declaredSize = null
            it.smtpUtf8 = false

            it.dsnEnvid = null
            it.dsnRet = null
            it.rcptDsn = mutableMapOf()
        }
    }
}
