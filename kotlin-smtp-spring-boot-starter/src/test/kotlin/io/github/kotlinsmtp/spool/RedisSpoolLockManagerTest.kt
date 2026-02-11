package io.github.kotlinsmtp.spool

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.nio.file.Path
import java.time.Duration

class RedisSpoolLockManagerTest {

    /**
     * Redis `setIfAbsent`가 true를 반환하면 락 획득에 성공해야 합니다.
     */
    @Test
    fun `try lock succeeds when setIfAbsent returns true`() {
        val template = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val valueOps = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(template.opsForValue()).thenReturn(valueOps)
        Mockito.`when`(
            valueOps.setIfAbsent(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Duration::class.java),
            )
        ).thenReturn(true)

        val manager = RedisSpoolLockManager(template, "test:spool", Duration.ofMinutes(5))
        val rawPath = Path.of("msg-1.eml")

        assertTrue(manager.tryLock(rawPath))
    }

    /**
     * 락 획득 전 refresh는 실패해야 합니다.
     */
    @Test
    fun `refresh lock fails without ownership`() {
        val template = Mockito.mock(StringRedisTemplate::class.java)
        val manager = RedisSpoolLockManager(template, "test:spool", Duration.ofMinutes(5))

        assertFalse(manager.refreshLock(Path.of("msg-2.eml")))
    }
}
