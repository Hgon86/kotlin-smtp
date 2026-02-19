package io.github.kotlinsmtp.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
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
) {
    private data class FailureRecord(
        val failures: MutableList<Long> = mutableListOf(),
        var lockedUntil: Long? = null,
    )

    private val records = ConcurrentHashMap<String, FailureRecord>()

    private fun makeKey(clientIp: String?, username: String): String {
        // Treat as "unknown" if IP is not present
        val ip = clientIp ?: "unknown"
        return "$ip:$username"
    }

    /**
     * Check lock status before authentication attempt
     * @return Remaining seconds if locked, null otherwise
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
            // Lock expired
            record.lockedUntil = null
        }
        return null
    }

    /**
     * Record authentication failure
     * @return true if locked
     */
    fun recordFailure(clientIp: String?, username: String): Boolean {
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
                log.warn { "Auth rate limit: Locked $username from $clientIp for ${lockoutDurationSeconds}s after ${record.failures.size} failures" }
                return true
            }
        }
        
        return false
    }

    /**
     * Reset records on authentication success
     */
    fun recordSuccess(clientIp: String?, username: String) {
        val key = makeKey(clientIp, username)
        records.remove(key)
        log.debug { "Auth rate limit: Cleared failures for $username from $clientIp" }
    }

    /**
     * Periodic cleanup (prevent memory leaks)
     */
    fun cleanup() {
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
}
