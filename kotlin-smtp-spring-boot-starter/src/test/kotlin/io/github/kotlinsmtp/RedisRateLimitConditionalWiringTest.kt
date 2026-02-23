package io.github.kotlinsmtp

import io.github.kotlinsmtp.auth.SmtpAuthRateLimiter
import io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration
import io.github.kotlinsmtp.server.SmtpRateLimiter
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisRateLimitConditionalWiringTest {

    /** Redis 백엔드를 선택했는데 Redis 템플릿이 없으면 시작을 실패해야 한다. */
    @Test
    fun `redis connection rate limit without redis template fails`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(*(minimalProps() + "smtp.rateLimit.backend=redis"))
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    /** 사용자 정의 limiter 빈이 있으면 Redis 템플릿 없이도 시작할 수 있어야 한다. */
    @Test
    fun `redis connection rate limit allows custom limiter bean`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withBean(SmtpRateLimiter::class.java, { NoopSmtpRateLimiter() })
            .withPropertyValues(*(minimalProps() + "smtp.rateLimit.backend=redis"))
            .run { context ->
                assertNull(context.startupFailure)
            }
    }

    /** AUTH Redis 백엔드를 선택했는데 Redis 템플릿이 없으면 시작을 실패해야 한다. */
    @Test
    fun `redis auth rate limit without redis template fails`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(*(minimalProps() + "smtp.auth.rateLimitBackend=redis"))
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    /** 사용자 정의 AUTH limiter 빈이 있으면 Redis 템플릿 없이도 시작할 수 있어야 한다. */
    @Test
    fun `redis auth rate limit allows custom limiter bean`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withBean(SmtpAuthRateLimiter::class.java, { NoopSmtpAuthRateLimiter() })
            .withPropertyValues(*(minimalProps() + "smtp.auth.rateLimitBackend=redis"))
            .run { context ->
                assertNull(context.startupFailure)
            }
    }

    private fun minimalProps(): Array<String> = arrayOf(
        "smtp.hostname=localhost",
        "smtp.port=0",
        "smtp.routing.localDomain=local.test",
        "smtp.storage.mailboxDir=./build/test-mailboxes",
        "smtp.storage.tempDir=./build/test-temp",
        "smtp.storage.listsDir=./build/test-lists",
        "smtp.spool.dir=./build/test-spool",
    )

    private class NoopSmtpRateLimiter : SmtpRateLimiter {
        override fun allowConnection(ipAddress: String): Boolean = true
        override fun releaseConnection(ipAddress: String) = Unit
        override fun allowMessage(ipAddress: String): Boolean = true
        override fun cleanup() = Unit
    }

    private class NoopSmtpAuthRateLimiter : SmtpAuthRateLimiter {
        override fun checkLock(clientIp: String?, username: String): Long? = null
        override fun recordFailure(clientIp: String?, username: String): Boolean = false
        override fun recordSuccess(clientIp: String?, username: String) = Unit
        override fun cleanup() = Unit
    }
}
