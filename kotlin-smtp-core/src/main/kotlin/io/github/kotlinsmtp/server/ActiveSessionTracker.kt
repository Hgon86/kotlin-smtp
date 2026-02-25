package io.github.kotlinsmtp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Active SMTP session tracker
 *
 * Used to wait until all sessions are closed during graceful shutdown.
 */
internal class ActiveSessionTracker {
    private val activeSessions = ConcurrentHashMap<String, SmtpSession>()

    /**
     * Register session
     */
    fun register(sessionId: String, session: SmtpSession) {
        activeSessions[sessionId] = session
        log.debug { "Session registered: $sessionId (total: ${activeSessions.size})" }
    }

    /**
     * Unregister session
     */
    fun unregister(sessionId: String) {
        activeSessions.remove(sessionId)
        log.debug { "Session unregistered: $sessionId (total: ${activeSessions.size})" }
    }

    /**
     * Current active session count
     */
    fun count(): Int = activeSessions.size

    /**
     * Request close for all sessions
     */
    fun closeAllSessions() {
        val sessions = mutableListOf<SmtpSession>()
        val iterator = activeSessions.values.iterator()
        while (iterator.hasNext()) {
            sessions += iterator.next()
        }
        log.info { "Requesting close for ${sessions.size} active sessions" }
        sessions.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                log.warn(e) { "Error closing session during graceful shutdown" }
            }
        }
    }

    /**
     * Wait until all sessions are closed (with timeout)
     * @return true if all sessions closed, false on timeout
     */
    suspend fun awaitAllSessionsClosed(timeoutMs: Long = 30000): Boolean {
        val startTime = System.currentTimeMillis()
        while (activeSessions.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                log.warn { "Timeout waiting for sessions to close. ${activeSessions.size} sessions still active" }
                return false
            }
            kotlinx.coroutines.delay(100)
        }
        log.info { "All sessions closed gracefully" }
        return true
    }
}
