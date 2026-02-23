package io.github.kotlinsmtp.ratelimit

import io.github.kotlinsmtp.server.SmtpRateLimiter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val redisRateLimitLog = KotlinLogging.logger {}

/**
 * Implements distributed connection/message limits using Redis.
 */
internal class RedisSmtpRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
    private val maxConnectionsPerIp: Int,
    private val maxMessagesPerIpPerHour: Int,
    private val connectionCounterTtl: Duration,
) : SmtpRateLimiter {
    override fun allowConnection(ipAddress: String): Boolean = runCatching {
        val result = redisTemplate.execute(
            ALLOW_CONNECTION_SCRIPT,
            listOf(connectionKey(ipAddress)),
            maxConnectionsPerIp.toString(),
            connectionCounterTtl.seconds.toString(),
        ) ?: 0L
        result > 0L
    }.getOrElse { error ->
        redisRateLimitLog.warn(error) { "Redis connection limit check failed, allowing: ip=$ipAddress" }
        true
    }

    override fun releaseConnection(ipAddress: String) {
        runCatching {
            redisTemplate.execute(RELEASE_CONNECTION_SCRIPT, listOf(connectionKey(ipAddress)))
        }.onFailure { error ->
            redisRateLimitLog.warn(error) { "Redis connection release failed: ip=$ipAddress" }
        }
    }

    override fun allowMessage(ipAddress: String): Boolean = runCatching {
        val nowMillis = Instant.now().toEpochMilli()
        val uniqueMember = "$nowMillis:${UUID.randomUUID()}"
        val result = redisTemplate.execute(
            ALLOW_MESSAGE_SCRIPT,
            listOf(messageKey(ipAddress)),
            maxMessagesPerIpPerHour.toString(),
            nowMillis.toString(),
            ONE_HOUR_MILLIS.toString(),
            uniqueMember,
            MESSAGE_KEY_EXPIRE_SECONDS.toString(),
        ) ?: 0L
        result > 0L
    }.getOrElse { error ->
        redisRateLimitLog.warn(error) { "Redis message limit check failed, allowing: ip=$ipAddress" }
        true
    }

    override fun cleanup() = Unit

    private fun connectionKey(ipAddress: String): String = "$keyPrefix:connection:$ipAddress"

    private fun messageKey(ipAddress: String): String = "$keyPrefix:message:$ipAddress"

    private companion object {
        private const val ONE_HOUR_MILLIS: Long = 3_600_000
        private const val MESSAGE_KEY_EXPIRE_SECONDS: Long = 3_900

        private val ALLOW_CONNECTION_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                local updated = redis.call('incr', KEYS[1])
                local maxAllowed = tonumber(ARGV[1])
                if updated > maxAllowed then
                    redis.call('decr', KEYS[1])
                    return 0
                end
                if updated == 1 then
                    redis.call('expire', KEYS[1], tonumber(ARGV[2]))
                end
                return 1
                """.trimIndent(),
            )
            resultType = Long::class.java
        }

        private val RELEASE_CONNECTION_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                local current = tonumber(redis.call('get', KEYS[1]) or '0')
                if current <= 1 then
                    redis.call('del', KEYS[1])
                    return 0
                end
                return redis.call('decr', KEYS[1])
                """.trimIndent(),
            )
            resultType = Long::class.java
        }

        private val ALLOW_MESSAGE_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                local key = KEYS[1]
                local maxAllowed = tonumber(ARGV[1])
                local nowMillis = tonumber(ARGV[2])
                local windowMillis = tonumber(ARGV[3])
                local member = ARGV[4]
                local ttlSeconds = tonumber(ARGV[5])
                local windowStart = nowMillis - windowMillis

                redis.call('zremrangebyscore', key, '-inf', windowStart)
                local current = redis.call('zcard', key)
                if current >= maxAllowed then
                    return 0
                end

                redis.call('zadd', key, nowMillis, member)
                redis.call('expire', key, ttlSeconds)
                return 1
                """.trimIndent(),
            )
            resultType = Long::class.java
        }
    }
}
