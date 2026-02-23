package io.github.kotlinsmtp.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SmtpServerPropertiesRedisValidationTest {

    /**
     * Validation must fail when `maxRawBytes <= 0` for Redis backend.
     */
    @Test
    fun `redis maxRawBytes must be positive`() {
        val props = baseProps().apply {
            spool.type = SmtpServerProperties.SpoolConfig.SpoolType.REDIS
            spool.redis.maxRawBytes = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Validation must fail when `lockTtlSeconds <= 0` for Redis backend.
     */
    @Test
    fun `redis lockTtlSeconds must be positive`() {
        val props = baseProps().apply {
            spool.type = SmtpServerProperties.SpoolConfig.SpoolType.REDIS
            spool.redis.lockTtlSeconds = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Validation must fail when `triggerCooldownMillis < 0`.
     */
    @Test
    fun `spool triggerCooldownMillis must be non-negative`() {
        val props = baseProps().apply {
            spool.triggerCooldownMillis = -1
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Validation must fail when spool worker concurrency is not positive.
     */
    @Test
    fun `spool workerConcurrency must be positive`() {
        val props = baseProps().apply {
            spool.workerConcurrency = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Validation must fail when graceful shutdown timeout is not positive.
     */
    @Test
    fun `lifecycle graceful shutdown timeout must be positive`() {
        val props = baseProps().apply {
            lifecycle.gracefulShutdownTimeoutMs = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Validation must fail when plaintext credentials are configured while disallowed.
     */
    @Test
    fun `auth plaintext credentials must be rejected when disallowed`() {
        val props = baseProps().apply {
            auth.allowPlaintextPasswords = false
            auth.users = mapOf("user" to "password")
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Validation must fail when Redis auth limiter prefix is blank.
     */
    @Test
    fun `auth redis rate limit prefix must not be blank`() {
        val props = baseProps().apply {
            auth.rateLimitBackend = SmtpServerProperties.RateLimitBackend.REDIS
            auth.rateLimitRedis.keyPrefix = "  "
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Validation must fail when Redis connection counter TTL is not positive.
     */
    @Test
    fun `redis connection counter ttl must be positive`() {
        val props = baseProps().apply {
            rateLimit.backend = SmtpServerProperties.RateLimitBackend.REDIS
            rateLimit.redis.connectionCounterTtlSeconds = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            props.validate()
        }
    }

    /**
     * Builds minimum valid properties for test setup.
     *
     * @return valid SMTP properties
     */
    private fun baseProps(): SmtpServerProperties = SmtpServerProperties().apply {
        hostname = "localhost"
        port = 2525
        routing.localDomain = "local.test"
        storage.mailboxDir = "./build/test-mailbox"
        storage.tempDir = "./build/test-temp"
        storage.listsDir = "./build/test-lists"
        spool.dir = "./build/test-spool"
    }
}
