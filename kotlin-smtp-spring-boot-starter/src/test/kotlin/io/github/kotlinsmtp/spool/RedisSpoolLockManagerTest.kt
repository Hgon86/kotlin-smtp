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
     * Lock acquisition should succeed when Redis `setIfAbsent` returns true.
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
     * Refresh should fail before lock ownership is acquired.
     */
    @Test
    fun `refresh lock fails without ownership`() {
        val template = Mockito.mock(StringRedisTemplate::class.java)
        val manager = RedisSpoolLockManager(template, "test:spool", Duration.ofMinutes(5))

        assertFalse(manager.refreshLock(Path.of("msg-2.eml")))
    }
}
