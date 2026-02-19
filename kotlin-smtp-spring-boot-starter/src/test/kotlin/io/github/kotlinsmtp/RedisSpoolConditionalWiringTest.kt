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
     * Boot must fail for Redis backend when no Redis template and no custom spool beans are provided.
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
     * Boot should succeed for Redis backend without Redis template when custom spool beans exist.
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
     * AUTO type should choose file spool store when Redis template is absent.
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
     * Returns minimal properties for smoke wiring tests.
     *
     * @return required property array
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
     * No-op spool metadata store for tests.
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
     * No-op spool lock manager for tests.
     */
    private class NoopSpoolLockManager : SpoolLockManager {
        override fun tryLock(rawPath: Path): Boolean = true
        override fun unlock(rawPath: Path) = Unit
        override fun purgeOrphanedLocks() = Unit
    }
}
