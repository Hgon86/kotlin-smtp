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
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool"))

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.routing.localDomain=local.test",
                "smtp.storage.mailboxDir=${mailboxDir.toString()}",
                "smtp.storage.tempDir=${messageTempDir.toString()}",
                "smtp.storage.listsDir=${listsDir.toString()}",
                "smtp.spool.dir=${spoolDir.toString()}",
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
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes-hook"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp-hook"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists-hook"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool-hook"))

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withBean(SmtpEventHook::class.java, { object : SmtpEventHook {} })
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.routing.localDomain=local.test",
                "smtp.storage.mailboxDir=${mailboxDir.toString()}",
                "smtp.storage.tempDir=${messageTempDir.toString()}",
                "smtp.storage.listsDir=${listsDir.toString()}",
                "smtp.spool.dir=${spoolDir.toString()}",
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
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes-doc"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp-doc"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists-doc"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool-doc"))

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                // docs/application.example.yml 핵심 키들과 정합을 유지하기 위한 스모크 테스트
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.serviceName=ESMTP",

                "smtp.routing.localDomain=mydomain.com",

                "smtp.storage.mailboxDir=${mailboxDir.toString()}",
                "smtp.storage.tempDir=${messageTempDir.toString()}",
                "smtp.storage.listsDir=${listsDir.toString()}",
                "smtp.spool.dir=${spoolDir.toString()}",

                // AUTH + rate limit (docs와 동기화 목적)
                "smtp.auth.enabled=true",
                "smtp.auth.required=false",
                "smtp.auth.rateLimitEnabled=true",
                "smtp.auth.rateLimitMaxFailures=5",
                "smtp.auth.rateLimitWindowSeconds=300",
                "smtp.auth.rateLimitLockoutSeconds=600",
                "smtp.auth.users.user=password",

                // relay는 기본 비활성(relay starter 없어도 부팅 가능해야 함)
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
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes-listeners"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp-listeners"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists-listeners"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool-listeners"))

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=2525", // listeners가 있으면 무시되어야 함
                "smtp.serviceName=ESMTP",

                "smtp.routing.localDomain=local.test",

                "smtp.storage.mailboxDir=${mailboxDir.toString()}",
                "smtp.storage.tempDir=${messageTempDir.toString()}",
                "smtp.storage.listsDir=${listsDir.toString()}",
                "smtp.spool.dir=${spoolDir.toString()}",

                "smtp.auth.enabled=true",
                "smtp.auth.required=false",

                // 2개의 리스너(포트 0으로 바인드)
                "smtp.listeners[0].port=0",
                "smtp.listeners[0].serviceName=SUBMISSION",
                "smtp.listeners[0].implicitTls=false",
                "smtp.listeners[0].enableStartTls=true",
                "smtp.listeners[0].enableAuth=true",
                "smtp.listeners[0].requireAuthForMail=true",

                "smtp.listeners[1].port=0",
                // serviceName 미지정: smtp.serviceName으로 fallback
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
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes-nodomain"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp-nodomain"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists-nodomain"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool-nodomain"))

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",

                // intentionally omit smtp.routing.localDomain
                "smtp.storage.mailboxDir=${mailboxDir.toString()}",
                "smtp.storage.tempDir=${messageTempDir.toString()}",
                "smtp.storage.listsDir=${listsDir.toString()}",
                "smtp.spool.dir=${spoolDir.toString()}",
            )
            .run { context ->
                assertTrue(context.startupFailure?.message?.contains("smtp.routing.localDomain") == true)
            }
    }

    @Test
    fun `ssl enabled without existing files fails fast at boot`() {
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes-ssl"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp-ssl"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists-ssl"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool-ssl"))

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",
                "smtp.routing.localDomain=local.test",

                "smtp.storage.mailboxDir=${mailboxDir.toString()}",
                "smtp.storage.tempDir=${messageTempDir.toString()}",
                "smtp.storage.listsDir=${listsDir.toString()}",
                "smtp.spool.dir=${spoolDir.toString()}",

                "smtp.ssl.enabled=true",
                "smtp.ssl.certChainFile=${tempDir.resolve("missing.crt")}",
                "smtp.ssl.privateKeyFile=${tempDir.resolve("missing.key")}",
            )
            .run { context ->
                assertTrue(context.startupFailure?.message?.contains("smtp.ssl.") == true)
            }
    }
}
