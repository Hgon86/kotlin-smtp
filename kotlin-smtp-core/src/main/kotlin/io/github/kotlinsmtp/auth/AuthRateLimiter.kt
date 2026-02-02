package io.github.kotlinsmtp.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * 공유 인증 Rate Limiter
 * 
 * - 세션 재접속으로 우회되는 세션-스코프 잠금을 방지하기 위해,
 *   (clientIp, username) 단위로 실패 카운트/잠금을 관리합니다.
 * - 메모리 기반 구현이며, 분산 환경에서는 Redis 등으로 교체 필요.
 */
class AuthRateLimiter(
    private val maxFailuresPerWindow: Int = 5,
    private val windowSeconds: Long = 300, // 5분
    private val lockoutDurationSeconds: Long = 600, // 10분
) {
    data class FailureRecord(
        val failures: MutableList<Long> = mutableListOf(),
        var lockedUntil: Long? = null,
    )

    private val records = ConcurrentHashMap<String, FailureRecord>()

    private fun makeKey(clientIp: String?, username: String): String {
        // IP가 없는 경우 "unknown"으로 처리
        val ip = clientIp ?: "unknown"
        return "$ip:$username"
    }

    /**
     * 인증 시도 전 잠금 상태 확인
     * @return 잠금 상태일 경우 남은 초 수, 아니면 null
     */
    fun checkLock(clientIp: String?, username: String): Long? {
        val key = makeKey(clientIp, username)
        val record = records[key] ?: return null
        val lockedUntil = record.lockedUntil
        
        if (lockedUntil != null) {
            val now = Instant.now().epochSecond
            if (now < lockedUntil) {
                return lockedUntil - now
            }
            // 잠금 만료
            record.lockedUntil = null
        }
        return null
    }

    /**
     * 인증 실패 기록
     * @return 잠금이 걸렸을 경우 true
     */
    fun recordFailure(clientIp: String?, username: String): Boolean {
        val key = makeKey(clientIp, username)
        val now = Instant.now().epochSecond
        val windowStart = now - windowSeconds

        val record = records.computeIfAbsent(key) { FailureRecord() }
        
        synchronized(record) {
            // 오래된 실패 기록 제거
            record.failures.removeIf { it < windowStart }
            
            // 새 실패 추가
            record.failures.add(now)
            
            // 잠금 조건 확인
            if (record.failures.size >= maxFailuresPerWindow) {
                record.lockedUntil = now + lockoutDurationSeconds
                log.warn { "Auth rate limit: Locked $username from $clientIp for ${lockoutDurationSeconds}s after ${record.failures.size} failures" }
                return true
            }
        }
        
        return false
    }

    /**
     * 인증 성공 시 기록 초기화
     */
    fun recordSuccess(clientIp: String?, username: String) {
        val key = makeKey(clientIp, username)
        records.remove(key)
        log.debug { "Auth rate limit: Cleared failures for $username from $clientIp" }
    }

    /**
     * 주기적 정리 (메모리 누수 방지)
     */
    fun cleanup() {
        val now = Instant.now().epochSecond
        records.entries.removeIf { (_, record) ->
            synchronized(record) {
                // 잠금이 만료되고 실패 기록도 없는 경우 제거
                val lockedUntil = record.lockedUntil
                val isLocked = lockedUntil != null && now < lockedUntil
                val hasRecentFailures = record.failures.any { 
                    it > now - windowSeconds 
                }
                val shouldRemove = !isLocked && !hasRecentFailures
                if (shouldRemove) {
                    log.debug { "Auth rate limit: Cleaned up record" }
                }
                shouldRemove
            }
        }
    }
}
