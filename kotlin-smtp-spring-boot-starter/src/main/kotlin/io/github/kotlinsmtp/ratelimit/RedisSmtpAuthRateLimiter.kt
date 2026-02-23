package io.github.kotlinsmtp.ratelimit

import io.github.kotlinsmtp.auth.SmtpAuthRateLimiter
import io.github.kotlinsmtp.auth.maskIdentity
import io.github.kotlinsmtp.auth.maskIp
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.util.UUID

private val redisAuthRateLimitLog = KotlinLogging.logger {}

/**
 * Implements distributed AUTH failure tracking and lockouts using Redis.
 */
internal class RedisSmtpAuthRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
    private val maxFailuresPerWindow: Int,
    private val windowSeconds: Long,
    private val lockoutDurationSeconds: Long,
) : SmtpAuthRateLimiter {
    override fun checkLock(clientIp: String?, username: String): Long? {
        val key = lockKey(clientIp, username)
        return runCatching {
            val remaining = redisTemplate.execute(CHECK_LOCK_SCRIPT, listOf(key)) ?: -1L
            if (remaining > 0L) remaining else null
        }.getOrElse { error ->
            redisAuthRateLimitLog.warn(error) { "Redis auth lock check failed: user=${maskIdentity(username)} ip=${maskIp(clientIp)}" }
            null
        }
    }

    override fun recordFailure(clientIp: String?, username: String): Boolean {
        val keyBase = keyBase(clientIp, username)
        return runCatching {
            val result = redisTemplate.execute(
                RECORD_FAILURE_SCRIPT,
                listOf(failureKey(keyBase), lockKey(keyBase)),
                windowSeconds.toString(),
                maxFailuresPerWindow.toString(),
                lockoutDurationSeconds.toString(),
                UUID.randomUUID().toString(),
            ) ?: 0L
            result > 0L
        }.getOrElse { error ->
            redisAuthRateLimitLog.warn(error) {
                "Redis auth failure record failed: user=${maskIdentity(username)} ip=${maskIp(clientIp)}"
            }
            false
        }
    }

    override fun recordSuccess(clientIp: String?, username: String) {
        val keyBase = keyBase(clientIp, username)
        runCatching {
            redisTemplate.delete(listOf(failureKey(keyBase), lockKey(keyBase)))
        }.onFailure { error ->
            redisAuthRateLimitLog.warn(error) {
                "Redis auth success cleanup failed: user=${maskIdentity(username)} ip=${maskIp(clientIp)}"
            }
        }
    }

    override fun cleanup() = Unit

    private fun keyBase(clientIp: String?, username: String): String = "${clientIp ?: "unknown"}:$username"

    private fun lockKey(clientIp: String?, username: String): String = lockKey(keyBase(clientIp, username))

    private fun lockKey(keyBase: String): String = "$keyPrefix:auth:lock:$keyBase"

    private fun failureKey(keyBase: String): String = "$keyPrefix:auth:fail:$keyBase"

    private companion object {
        private val CHECK_LOCK_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                local lockKey = KEYS[1]
                local lockedUntil = tonumber(redis.call('get', lockKey) or '0')
                if lockedUntil <= 0 then
                    return -1
                end

                local now = tonumber(redis.call('time')[1])
                if lockedUntil > now then
                    return lockedUntil - now
                end
                return -1
                """.trimIndent(),
            )
            resultType = Long::class.java
        }

        private val RECORD_FAILURE_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                local failKey = KEYS[1]
                local lockKey = KEYS[2]
                local windowSeconds = tonumber(ARGV[1])
                local maxFailures = tonumber(ARGV[2])
                local lockoutSeconds = tonumber(ARGV[3])
                local memberSuffix = ARGV[4]

                local now = tonumber(redis.call('time')[1])
                local member = tostring(now) .. ':' .. memberSuffix

                local existingLock = tonumber(redis.call('get', lockKey) or '0')
                if existingLock > now then
                    return 1
                end

                local windowStart = now - windowSeconds
                redis.call('zremrangebyscore', failKey, '-inf', windowStart)
                redis.call('zadd', failKey, now, member)
                redis.call('expire', failKey, windowSeconds + lockoutSeconds)

                local failCount = redis.call('zcard', failKey)
                if failCount >= maxFailures then
                    local lockedUntil = now + lockoutSeconds
                    redis.call('set', lockKey, tostring(lockedUntil), 'EX', lockoutSeconds + windowSeconds)
                    return 1
                end

                return 0
                """.trimIndent(),
            )
            resultType = Long::class.java
        }
    }
}
