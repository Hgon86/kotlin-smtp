package io.github.kotlinsmtp

import io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration
import io.github.kotlinsmtp.relay.config.KotlinSmtpRelayAutoConfiguration
import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

class RelayStarterWiringTest {

    @TempDir
    lateinit var tempDir: Path

    private fun requiredProps(): Array<String> {
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailboxes"))
        val messageTempDir = Files.createDirectories(tempDir.resolve("message-temp"))
        val listsDir = Files.createDirectories(tempDir.resolve("lists"))
        val spoolDir = Files.createDirectories(tempDir.resolve("spool"))

        return arrayOf(
            "smtp.hostname=localhost",
            "smtp.port=0",
            "smtp.storage.mailboxDir=${mailboxDir.toString()}",
            "smtp.storage.tempDir=${messageTempDir.toString()}",
            "smtp.storage.listsDir=${listsDir.toString()}",
            "smtp.spool.dir=${spoolDir.toString()}",
        )
    }

    @Test
    fun `relay enabled without relay-starter should fail-fast`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KotlinSmtpAutoConfiguration::class.java))
            .withPropertyValues(
                *requiredProps(),
                "smtp.relay.enabled=true",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("MailRelay")
            }
    }

    @Test
    fun `relay enabled with relay-starter should start and override default policy`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    KotlinSmtpAutoConfiguration::class.java,
                    KotlinSmtpRelayAutoConfiguration::class.java,
                )
            )
            .withPropertyValues(
                *requiredProps(),
                "smtp.relay.enabled=true",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(MailRelay::class.java)
                assertThat(context).hasSingleBean(RelayAccessPolicy::class.java)
            }
    }
}
