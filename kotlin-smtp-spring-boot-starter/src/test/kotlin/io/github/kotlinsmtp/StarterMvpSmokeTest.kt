package io.github.kotlinsmtp

import io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration
import io.github.kotlinsmtp.server.SmtpServer
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
    fun `minimal properties start and stop server`() {
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool"))

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                "smtp.hostname=localhost",
                "smtp.port=0",
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
}
