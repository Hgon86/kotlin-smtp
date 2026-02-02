package com.crinity.kotlinsmtp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 활성 SMTP 세션 추적기
 * 
 * Graceful shutdown 시 모든 세션이 종료될 때까지 대기하기 위해 사용됩니다.
 */
class ActiveSessionTracker {
    private val activeSessions = ConcurrentHashMap<String, SmtpSession>()

    /**
     * 세션 등록
     */
    fun register(sessionId: String, session: SmtpSession) {
        activeSessions[sessionId] = session
        log.debug { "Session registered: $sessionId (total: ${activeSessions.size})" }
    }

    /**
     * 세션 제거
     */
    fun unregister(sessionId: String) {
        activeSessions.remove(sessionId)
        log.debug { "Session unregistered: $sessionId (total: ${activeSessions.size})" }
    }

    /**
     * 현재 활성 세션 수
     */
    fun count(): Int = activeSessions.size

    /**
     * 모든 세션 종료 요청
     */
    fun closeAllSessions() {
        val sessions = activeSessions.values.toList()
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
     * 모든 세션이 종료될 때까지 대기 (with timeout)
     * @return true if all sessions closed, false if timeout
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
