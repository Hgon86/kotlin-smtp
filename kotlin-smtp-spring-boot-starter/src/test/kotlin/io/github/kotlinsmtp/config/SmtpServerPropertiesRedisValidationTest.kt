package io.github.kotlinsmtp.config

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SmtpServerPropertiesRedisValidationTest {

    /**
     * Redis 백엔드에서 maxRawBytes가 0 이하면 검증 실패해야 합니다.
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
     * Redis 백엔드에서 lockTtlSeconds가 0 이하면 검증 실패해야 합니다.
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
     * 테스트에 필요한 최소 유효 설정을 구성합니다.
     *
     * @return 유효한 SMTP 설정
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
