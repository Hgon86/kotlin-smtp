package io.github.kotlinsmtp.spool

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

private val redisLockLog = KotlinLogging.logger {}

/**
 * Redis-based spool lock manager.
 *
 * Locks are created with `SET NX EX`, and stale locks are cleaned up by TTL.
 *
 * @property redisTemplate Redis string template
 * @property keyPrefix Redis key prefix
 * @property lockTtl lock TTL
 */
internal class RedisSpoolLockManager(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
    private val lockTtl: Duration,
) : SpoolLockManager {
    private val lockTokens = ConcurrentHashMap<String, String>()

    /**
     * Tries to acquire lock for target message file.
     *
     * @param rawPath target message file path
     * @return whether lock acquisition succeeded
     */
    override fun tryLock(rawPath: Path): Boolean = runCatching {
        val key = lockKey(rawPath)
        val token = UUID.randomUUID().toString()
        val locked = redisTemplate.opsForValue().setIfAbsent(key, token, lockTtl) == true
        if (locked) lockTokens[key] = token
        locked
    }.getOrElse { e ->
        redisLockLog.warn(e) { "Failed to create Redis spool lock: ${rawPath}" }
        false
    }

    /**
     * Releases lock for target message file.
     *
     * @param rawPath target message file path
     */
    override fun unlock(rawPath: Path) {
        val key = lockKey(rawPath)
        val token = lockTokens.remove(key) ?: return
        runCatching {
            redisTemplate.execute(UNLOCK_SCRIPT, listOf(key), token)
        }.onFailure { e ->
            redisLockLog.warn(e) { "Failed to release Redis spool lock safely: ${rawPath}" }
        }
    }

    /**
     * Refreshes lock TTL for target message file.
     *
     * @param rawPath target message file path
     * @return whether refresh succeeded
     */
    override fun refreshLock(rawPath: Path): Boolean {
        val key = lockKey(rawPath)
        val token = lockTokens[key] ?: return false
        return runCatching {
            val updated = redisTemplate.execute(
                REFRESH_SCRIPT,
                listOf(key),
                token,
                lockTtl.toMillis().toString(),
            ) ?: 0L
            updated > 0L
        }.getOrElse { e ->
            redisLockLog.warn(e) { "Failed to refresh Redis spool lock: ${rawPath}" }
            false
        }
    }

    /**
     * No explicit purge is needed because Redis TTL handles orphan locks.
     */
    override fun purgeOrphanedLocks() = Unit

    private fun lockKey(rawPath: Path): String = "$keyPrefix:lock:${rawPathToken(rawPath)}"

    private fun rawPathToken(rawPath: Path): String =
        RedisSpoolKeyCodec.pathToken(rawPath)

    companion object {
        private val UNLOCK_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                end
                return 0
                """.trimIndent(),
            )
            resultType = Long::class.java
        }

        private val REFRESH_SCRIPT = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('pexpire', KEYS[1], ARGV[2])
                end
                return 0
                """.trimIndent(),
            )
            resultType = Long::class.java
        }
    }
}
