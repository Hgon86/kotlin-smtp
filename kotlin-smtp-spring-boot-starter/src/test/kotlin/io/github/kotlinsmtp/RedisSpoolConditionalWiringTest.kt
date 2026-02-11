package io.github.kotlinsmtp

import io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration
import io.github.kotlinsmtp.spool.FileSpoolMetadataStore
import io.github.kotlinsmtp.spool.SpoolLockManager
import io.github.kotlinsmtp.spool.SpoolMetadata
import io.github.kotlinsmtp.spool.SpoolMetadataStore
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Path

class RedisSpoolConditionalWiringTest {

    /**
     * Redis 백엔드 선택 시 Redis 템플릿이 없고 커스텀 스풀 빈도 없으면 부팅이 실패해야 합니다.
     */
    @Test
    fun `redis type without redis template fails`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(*(minimalProps() + "smtp.spool.type=redis"))
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    /**
     * Redis 백엔드 선택 시 커스텀 스풀 빈이 있으면 Redis 템플릿 없이도 부팅 가능해야 합니다.
     */
    @Test
    fun `redis type allows custom spool beans without redis template`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withBean(SpoolMetadataStore::class.java, { NoopSpoolMetadataStore() })
            .withBean(SpoolLockManager::class.java, { NoopSpoolLockManager() })
            .withPropertyValues(*(minimalProps() + "smtp.spool.type=redis"))
            .run { context ->
                assertNull(context.startupFailure)
            }
    }

    /**
     * auto 타입에서 Redis 템플릿이 없으면 파일 스풀 저장소를 선택해야 합니다.
     */
    @Test
    fun `auto type falls back to file store without redis template`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(*minimalProps())
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBean(SpoolMetadataStore::class.java) is FileSpoolMetadataStore)
            }
    }

    /**
     * 스모크 테스트용 최소 설정을 반환합니다.
     *
     * @return 필수 속성 배열
     */
    private fun minimalProps(): Array<String> = arrayOf(
        "smtp.hostname=localhost",
        "smtp.port=0",
        "smtp.routing.localDomain=local.test",
        "smtp.storage.mailboxDir=./build/test-mailboxes",
        "smtp.storage.tempDir=./build/test-temp",
        "smtp.storage.listsDir=./build/test-lists",
        "smtp.spool.dir=./build/test-spool",
    )

    /**
     * 테스트용 no-op 스풀 메타데이터 저장소입니다.
     */
    private class NoopSpoolMetadataStore : SpoolMetadataStore {
        override fun initializeDirectory() = Unit
        override fun scanPendingMessageCount(): Long = 0
        override fun listMessages(): List<Path> = emptyList()
        override fun createMessage(
            rawMessagePath: Path,
            sender: String?,
            recipients: List<String>,
            messageId: String,
            authenticated: Boolean,
            peerAddress: String?,
            dsnRet: String?,
            dsnEnvid: String?,
            rcptDsn: Map<String, io.github.kotlinsmtp.model.RcptDsn>,
        ): SpoolMetadata = throw UnsupportedOperationException("Not needed in this wiring test")

        override fun writeMeta(meta: SpoolMetadata) = Unit
        override fun readMeta(rawPath: Path): SpoolMetadata? = null
        override fun removeMessage(rawPath: Path) = Unit
    }

    /**
     * 테스트용 no-op 스풀 락 관리자입니다.
     */
    private class NoopSpoolLockManager : SpoolLockManager {
        override fun tryLock(rawPath: Path): Boolean = true
        override fun unlock(rawPath: Path) = Unit
        override fun purgeOrphanedLocks() = Unit
    }
}
