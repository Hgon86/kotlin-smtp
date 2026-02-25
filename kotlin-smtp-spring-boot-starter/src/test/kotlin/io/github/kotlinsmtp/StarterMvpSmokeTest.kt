package io.github.kotlinsmtp

import io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration
import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.spi.SmtpEventHook
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptor
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorAction
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorContext
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandStage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.net.ServerSocket
import java.net.Socket

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
    fun `minimal properties start and stop server with command interceptor bean`() {
        val dirs = createTestDirectories("interceptor")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withBean(SmtpCommandInterceptor::class.java, {
                object : SmtpCommandInterceptor {
                    override suspend fun intercept(
                        stage: SmtpCommandStage,
                        context: SmtpCommandInterceptorContext,
                    ): SmtpCommandInterceptorAction = SmtpCommandInterceptorAction.Proceed
                }
            })
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
    fun `command interceptors respect spring order`() {
        val dirs = createTestDirectories("ordered-interceptor")
        val fixedPort = ServerSocket(0).use { it.localPort }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withUserConfiguration(OrderedInterceptorTestConfig::class.java)
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=$fixedPort",
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

                runBlocking {
                    assertTrue(server.start())
                }

                try {
                    Socket("localhost", fixedPort).use { socket ->
                        socket.soTimeout = 3_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val writer = OutputStreamWriter(socket.getOutputStream())

                        reader.readLine() // greeting
                        writer.write("EHLO test.client.local\r\n")
                        writer.flush()
                        skipEhloResponse(reader)

                        writer.write("MAIL FROM:<sender@test.com>\r\n")
                        writer.flush()

                        val denied = reader.readLine()
                        assertTrue(
                            denied.startsWith("553") && denied.contains("ordered-first"),
                            "Expected first ordered interceptor denial, got: $denied",
                        )
                    }
                } finally {
                    runBlocking {
                        assertTrue(server.stop())
                    }
                }
            }
    }

    @Test
    fun `command interceptor can deny recipient domain at RCPT stage`() {
        val dirs = createTestDirectories("rcpt-policy")
        val fixedPort = ServerSocket(0).use { it.localPort }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withUserConfiguration(RecipientDomainPolicyTestConfig::class.java)
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=$fixedPort",
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

                runBlocking {
                    assertTrue(server.start())
                }

                try {
                    Socket("localhost", fixedPort).use { socket ->
                        socket.soTimeout = 3_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val writer = OutputStreamWriter(socket.getOutputStream())

                        reader.readLine() // greeting

                        writer.write("EHLO test.client.local\r\n")
                        writer.flush()
                        skipEhloResponse(reader)

                        writer.write("MAIL FROM:<sender@test.com>\r\n")
                        writer.flush()
                        val mailResp = reader.readLine()
                        assertTrue(mailResp.startsWith("250"), "Expected 250 after MAIL FROM, got: $mailResp")

                        writer.write("RCPT TO:<user@blocked.example>\r\n")
                        writer.flush()
                        val denied = reader.readLine()
                        assertTrue(
                            denied.startsWith("550") && denied.contains("Recipient domain blocked"),
                            "Expected RCPT deny from interceptor, got: $denied",
                        )
                    }
                } finally {
                    runBlocking {
                        assertTrue(server.stop())
                    }
                }
            }
    }

    @Test
    fun `command interceptor can deny command at PRE_COMMAND stage`() {
        val dirs = createTestDirectories("pre-command-policy")
        val fixedPort = ServerSocket(0).use { it.localPort }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withUserConfiguration(PreCommandPolicyTestConfig::class.java)
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=$fixedPort",
                "smtp.routing.localDomain=local.test",
                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",
            )
            .run { context ->
                val server = (context.getBean("smtpServers") as List<*>).single() as SmtpServer

                runBlocking {
                    assertTrue(server.start())
                }

                try {
                    Socket("localhost", fixedPort).use { socket ->
                        socket.soTimeout = 3_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val writer = OutputStreamWriter(socket.getOutputStream())

                        reader.readLine() // greeting

                        writer.write("NOOP\r\n")
                        writer.flush()

                        val denied = reader.readLine()
                        assertTrue(
                            denied.startsWith("550") && denied.contains("NOOP disabled by policy"),
                            "Expected PRE_COMMAND deny, got: $denied",
                        )
                    }
                } finally {
                    runBlocking {
                        assertTrue(server.stop())
                    }
                }
            }
    }

    @Test
    fun `command interceptor can deny AUTH at AUTH stage`() {
        val dirs = createTestDirectories("auth-stage-policy")
        val fixedPort = ServerSocket(0).use { it.localPort }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withUserConfiguration(AuthStagePolicyTestConfig::class.java)
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=$fixedPort",
                "smtp.routing.localDomain=local.test",
                "smtp.storage.mailboxDir=${dirs.mailboxDir}",
                "smtp.storage.tempDir=${dirs.messageTempDir}",
                "smtp.storage.listsDir=${dirs.listsDir}",
                "smtp.spool.dir=${dirs.spoolDir}",
                "smtp.auth.enabled=true",
                "smtp.auth.users.user=password",
            )
            .run { context ->
                val server = (context.getBean("smtpServers") as List<*>).single() as SmtpServer

                runBlocking {
                    assertTrue(server.start())
                }

                try {
                    Socket("localhost", fixedPort).use { socket ->
                        socket.soTimeout = 3_000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val writer = OutputStreamWriter(socket.getOutputStream())

                        reader.readLine() // greeting

                        writer.write("EHLO test.client.local\r\n")
                        writer.flush()
                        skipEhloResponse(reader)

                        writer.write("AUTH PLAIN AHVzZXIAcGFzc3dvcmQ=\r\n")
                        writer.flush()

                        val denied = reader.readLine()
                        assertTrue(
                            denied.startsWith("535") && denied.contains("AUTH blocked by policy"),
                            "Expected AUTH stage deny, got: $denied",
                        )
                    }
                } finally {
                    runBlocking {
                        assertTrue(server.stop())
                    }
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

    private fun skipEhloResponse(reader: BufferedReader) {
        var line = reader.readLine()
        while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
            if (line.startsWith("250 ")) break
            line = reader.readLine()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class OrderedInterceptorTestConfig {
        @Bean
        @Order(-100)
        fun firstOrderedInterceptor(): SmtpCommandInterceptor = object : SmtpCommandInterceptor {

            override suspend fun intercept(
                stage: SmtpCommandStage,
                context: SmtpCommandInterceptorContext,
            ): SmtpCommandInterceptorAction {
                if (stage == SmtpCommandStage.MAIL_FROM) {
                    return SmtpCommandInterceptorAction.Deny(553, "5.7.1 ordered-first")
                }
                return SmtpCommandInterceptorAction.Proceed
            }
        }

        @Bean
        @Order(100)
        fun secondOrderedInterceptor(): SmtpCommandInterceptor = object : SmtpCommandInterceptor {

            override suspend fun intercept(
                stage: SmtpCommandStage,
                context: SmtpCommandInterceptorContext,
            ): SmtpCommandInterceptorAction {
                if (stage == SmtpCommandStage.MAIL_FROM) {
                    return SmtpCommandInterceptorAction.Deny(554, "5.7.1 ordered-second")
                }
                return SmtpCommandInterceptorAction.Proceed
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class RecipientDomainPolicyTestConfig {
        @Bean
        @Order(100)
        fun recipientDomainPolicyInterceptor(): SmtpCommandInterceptor = object : SmtpCommandInterceptor {
            override suspend fun intercept(
                stage: SmtpCommandStage,
                context: SmtpCommandInterceptorContext,
            ): SmtpCommandInterceptorAction {
                if (stage != SmtpCommandStage.RCPT_TO) {
                    return SmtpCommandInterceptorAction.Proceed
                }

                val recipient = context.rawCommand
                    .substringAfter('<', "")
                    .substringBefore('>', "")
                    .lowercase()

                val domain = recipient.substringAfterLast('@', "")
                return if (domain == "blocked.example") {
                    SmtpCommandInterceptorAction.Deny(550, "5.7.1 Recipient domain blocked")
                } else {
                    SmtpCommandInterceptorAction.Proceed
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class PreCommandPolicyTestConfig {
        @Bean
        @Order(100)
        fun preCommandPolicyInterceptor(): SmtpCommandInterceptor = object : SmtpCommandInterceptor {
            override suspend fun intercept(
                stage: SmtpCommandStage,
                context: SmtpCommandInterceptorContext,
            ): SmtpCommandInterceptorAction {
                return if (stage == SmtpCommandStage.PRE_COMMAND && context.commandName == "NOOP") {
                    SmtpCommandInterceptorAction.Deny(550, "5.7.1 NOOP disabled by policy")
                } else {
                    SmtpCommandInterceptorAction.Proceed
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class AuthStagePolicyTestConfig {
        @Bean
        fun authStagePolicyInterceptor(): SmtpCommandInterceptor = object : SmtpCommandInterceptor {
            override val order: Int = -2000

            override suspend fun intercept(
                stage: SmtpCommandStage,
                context: SmtpCommandInterceptorContext,
            ): SmtpCommandInterceptorAction {
                return if (stage == SmtpCommandStage.AUTH) {
                    SmtpCommandInterceptorAction.Deny(535, "5.7.8 AUTH blocked by policy")
                } else {
                    SmtpCommandInterceptorAction.Proceed
                }
            }
        }
    }

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
