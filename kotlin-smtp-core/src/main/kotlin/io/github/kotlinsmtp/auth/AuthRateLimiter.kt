package io.github.kotlinsmtp.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Shared Authentication Rate Limiter
 *
 * - To prevent session-scoped locks from being bypassed by session reconnection,
 *   failure count/lock is managed per (clientIp, username).
 * - Memory-based implementation; should be replaced with Redis, etc. in distributed environments.
 */
internal class AuthRateLimiter(
    private val maxFailuresPerWindow: Int = 5,
    private val windowSeconds: Long = 300, // 5 minutes
    private val lockoutDurationSeconds: Long = 600, // 10 minutes
) : SmtpAuthRateLimiter {
    private data class FailureRecord(
        val failures: MutableList<Long> = mutableListOf(),
        var lockedUntil: Long? = null,
    )

    private val records = ConcurrentHashMap<String, FailureRecord>()

    private fun makeKey(clientIp: String?, username: String): String = "${clientIp ?: "unknown"}:$username"

    /**
     * Check lock status before authentication attempt
     * @return Remaining seconds if locked, null otherwise
     */
    override fun checkLock(clientIp: String?, username: String): Long? {
        val key = makeKey(clientIp, username)
        val record = records[key] ?: return null
        synchronized(record) {
            val lockedUntil = record.lockedUntil ?: return null
            val now = Instant.now().epochSecond
            if (now < lockedUntil) {
                return lockedUntil - now
            }
            // Lock expired
            record.lockedUntil = null
            return null
        }
    }

    /**
     * Record authentication failure
     * @return true if locked
     */
    override fun recordFailure(clientIp: String?, username: String): Boolean {
        val key = makeKey(clientIp, username)
        val now = Instant.now().epochSecond
        val windowStart = now - windowSeconds

        val record = records.computeIfAbsent(key) { FailureRecord() }
        
        synchronized(record) {
            // DO NOT REMOVE: protects the mutable failures list inside the record.
            // Remove old failure records
            record.failures.removeIf { it < windowStart }

            // Add new failure
            record.failures.add(now)

            // Check lock condition
            if (record.failures.size >= maxFailuresPerWindow) {
                record.lockedUntil = now + lockoutDurationSeconds
                log.warn {
                    "Auth rate limit: Locked user='${maskIdentity(username)}' ip='${maskIp(clientIp)}' for ${lockoutDurationSeconds}s after ${record.failures.size} failures"
                }
                return true
            }
        }
        
        return false
    }

    /**
     * Reset records on authentication success
     */
    override fun recordSuccess(clientIp: String?, username: String) {
        val key = makeKey(clientIp, username)
        records.remove(key)
        log.debug { "Auth rate limit: Cleared failures for user='${maskIdentity(username)}' ip='${maskIp(clientIp)}'" }
    }

    /**
     * Periodic cleanup (prevent memory leaks)
     */
    override fun cleanup() {
        val now = Instant.now().epochSecond
        records.entries.removeIf { (_, record) ->
            synchronized(record) {
                // Remove if lock expired and no failure records
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

    /**
     * Masks username for safer authentication logging.
     */
    private fun maskIdentity(value: String): String {
        if (value.isBlank()) return "unknown"
        if (value.length <= 2) return "**"
        return "${value.take(2)}***"
    }

    /**
     * Masks client IP for safer authentication logging.
     */
    private fun maskIp(value: String?): String {
        val ip = value?.trim().orEmpty()
        if (ip.isEmpty()) return "unknown"
        val v4 = ip.split('.')
        if (v4.size == 4) return "${v4[0]}.${v4[1]}.*.*"
        val v6 = ip.split(':').filter { it.isNotEmpty() }
        if (v6.size >= 2) return "${v6[0]}:${v6[1]}:*"
        return "masked"
    }
}
