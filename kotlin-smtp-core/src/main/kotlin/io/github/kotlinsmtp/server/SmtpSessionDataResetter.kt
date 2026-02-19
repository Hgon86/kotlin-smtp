package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.model.SessionData

/**
 * Rebuild SessionData when resetting transaction state.
 *
 * - Reflects requirements to reset auth/session state (e.g., after STARTTLS).
 * - For stronger security, ESMTP/DSN parameters are reset per transaction.
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
        val authenticatedUsername = if (preserveAuth) current.authenticatedUsername else null
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
            it.authenticatedUsername = authenticatedUsername
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
