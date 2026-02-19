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
