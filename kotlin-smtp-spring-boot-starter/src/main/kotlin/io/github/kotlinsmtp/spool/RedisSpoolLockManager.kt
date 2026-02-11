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
 * Redis 기반 스풀 락 관리자입니다.
 *
 * 락은 `SET NX EX` 기반으로 생성되며 TTL로 stale 락을 자동 정리합니다.
 *
 * @property redisTemplate Redis 문자열 템플릿
 * @property keyPrefix Redis 키 접두사
 * @property lockTtl 락 TTL
 */
internal class RedisSpoolLockManager(
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
    private val lockTtl: Duration,
) : SpoolLockManager {
    private val lockTokens = ConcurrentHashMap<String, String>()

    /**
     * 대상 메시지 파일에 대한 락 획득을 시도합니다.
     *
     * @param rawPath 대상 메시지 파일 경로
     * @return 락 획득 성공 여부
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
     * 대상 메시지 파일의 락을 해제합니다.
     *
     * @param rawPath 대상 메시지 파일 경로
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
     * 대상 메시지 파일의 락 TTL을 연장합니다.
     *
     * @param rawPath 대상 메시지 파일 경로
     * @return 연장 성공 여부
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
     * Redis TTL을 사용하므로 별도 정리 작업은 수행하지 않습니다.
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
