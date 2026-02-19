package io.github.kotlinsmtp

import io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration
import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.spi.SmtpEventHook
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

class StarterMvpSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `minimal properties start and stop server without hooks`() {
        val dirs = createTestDirectories("base")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.routing.localDomain=local.test",
                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",
            )
            .run { context ->
                val servers = context.getBean("smtpServers") as List<*>
                assertEquals(1, servers.size)

                val server = servers.single() as SmtpServer
                assertEquals(0, server.port)
                assertEquals("localhost", server.hostname)

                runBlocking {
                    assertTrue(server.start())
                    assertTrue(server.stop())
                }
            }
    }

    @Test
    fun `minimal properties start and stop server with hook bean`() {
        val dirs = createTestDirectories("hook")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withBean(SmtpEventHook::class.java, { object : SmtpEventHook {} })
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.routing.localDomain=local.test",
                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",
            )
            .run { context ->
                val servers = context.getBean("smtpServers") as List<*>
                assertEquals(1, servers.size)

                val server = servers.single() as SmtpServer
                assertEquals(0, server.port)
                assertEquals("localhost", server.hostname)

                runBlocking {
                    assertTrue(server.start())
                    assertTrue(server.stop())
                }
            }
    }

    @Test
    fun `documented example properties boot in single-port mode`() {
        val dirs = createTestDirectories("doc")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                // Smoke test to stay aligned with key docs/application.example.yml properties.
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.serviceName=ESMTP",

                "smtp.routing.localDomain=mydomain.com",

                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",

                // AUTH + rate limit (kept in sync with docs)
                "smtp.auth.enabled=true",
                "smtp.auth.required=false",
                "smtp.auth.rateLimitEnabled=true",
                "smtp.auth.rateLimitMaxFailures=5",
                "smtp.auth.rateLimitWindowSeconds=300",
                "smtp.auth.rateLimitLockoutSeconds=600",
                "smtp.auth.users.user=password",

                // Relay is disabled by default (should boot without relay starter).
                "smtp.relay.enabled=false",
            )
            .run { context ->
                val servers = context.getBean("smtpServers") as List<*>
                assertEquals(1, servers.size)

                val server = servers.single() as SmtpServer
                assertEquals(0, server.port)
                assertEquals("localhost", server.hostname)
                assertEquals("ESMTP", server.serviceName)

                runBlocking {
                    assertTrue(server.start())
                    assertTrue(server.stop())
                }
            }
    }

    @Test
    fun `listeners mode ignores smtp port and creates multiple servers`() {
        val dirs = createTestDirectories("listeners")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=2525", // should be ignored when listeners are configured
                "smtp.serviceName=ESMTP",

                "smtp.routing.localDomain=local.test",

                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",

                "smtp.auth.enabled=true",
                "smtp.auth.required=false",

                // Two listeners (bind with port 0).
                "smtp.listeners[0].port=0",
                "smtp.listeners[0].serviceName=SUBMISSION",
                "smtp.listeners[0].implicitTls=false",
                "smtp.listeners[0].enableStartTls=true",
                "smtp.listeners[0].enableAuth=true",
                "smtp.listeners[0].requireAuthForMail=true",

                "smtp.listeners[1].port=0",
                // serviceName omitted: fallback to smtp.serviceName.
                "smtp.listeners[1].implicitTls=false",
                "smtp.listeners[1].enableStartTls=false",
                "smtp.listeners[1].enableAuth=true",
                "smtp.listeners[1].requireAuthForMail=false",
            )
            .run { context ->
                val servers = context.getBean("smtpServers") as List<*>
                assertEquals(2, servers.size)

                val s1 = servers[0] as SmtpServer
                val s2 = servers[1] as SmtpServer

                assertEquals(0, s1.port)
                assertEquals(0, s2.port)
                assertEquals("localhost", s1.hostname)
                assertEquals("localhost", s2.hostname)
                assertEquals("SUBMISSION", s1.serviceName)
                assertEquals("ESMTP", s2.serviceName)

                runBlocking {
                    assertTrue(s1.start())
                    assertTrue(s2.start())

                    assertTrue(s1.stop())
                    assertTrue(s2.stop())
                }
            }
    }

    @Test
    fun `missing local domain fails fast at boot`() {
        val dirs = createTestDirectories("nodomain")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",

                // intentionally omit smtp.routing.localDomain
                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",
            )
            .run { context ->
                assertTrue(context.startupFailure?.message?.contains("smtp.routing.localDomain") == true)
            }
    }

    @Test
    fun `ssl enabled without existing files fails fast at boot`() {
        val dirs = createTestDirectories("ssl")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.routing.localDomain=local.test",

                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",

                "smtp.ssl.enabled=true",
                "smtp.ssl.certChainFile=${tempDir.resolve("missing.crt")}",
                "smtp.ssl.privateKeyFile=${tempDir.resolve("missing.key")}",
            )
            .run { context ->
                assertTrue(context.startupFailure?.message?.contains("smtp.ssl.") == true)
            }
    }

    @Test
    fun `invalid port fails fast at boot`() {
        val dirs = createTestDirectories("port")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=70000", // invalid port
                "smtp.routing.localDomain=local.test",

                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",
            )
            .run { context ->
                assertTrue(context.startupFailure?.message?.contains("port") == true)
            }
    }

    @Test
    fun `invalid listener port fails fast at boot`() {
        val dirs = createTestDirectories("lport")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.routing.localDomain=local.test",

                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",

                "smtp.listeners[0].port=99999", // invalid port
            )
            .run { context ->
                assertTrue(context.startupFailure?.message?.contains("port") == true)
            }
    }

    /**
     * Creates a bundle of temporary directories per test case.
     *
     * @param suffix test-specific suffix
     * @return created test directory bundle
     */
    private fun createTestDirectories(suffix: String): TestDirectories = TestDirectories(
        mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes-$suffix")),
        messageTempDir = Files.createDirectories(tempDir.resolve("message-temp-$suffix")),
        listsDir = Files.createDirectories(tempDir.resolve("lists-$suffix")),
        spoolDir = Files.createDirectories(tempDir.resolve("spool-$suffix")),
    )

    /**
     * Directory path bundle for smoke tests.
     *
     * @property mailboxDir local mailbox directory
     * @property messageTempDir temporary message storage directory
     * @property listsDir mailing-list file directory
     * @property spoolDir spool directory
     */
    private data class TestDirectories(
        val mailboxDir: Path,
        val messageTempDir: Path,
        val listsDir: Path,
        val spoolDir: Path,
    )
}
