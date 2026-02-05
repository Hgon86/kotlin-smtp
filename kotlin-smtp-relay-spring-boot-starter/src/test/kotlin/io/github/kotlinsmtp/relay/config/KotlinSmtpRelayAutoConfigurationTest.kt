package io.github.kotlinsmtp.relay.config

import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.DsnStore
import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class KotlinSmtpRelayAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KotlinSmtpRelayAutoConfiguration::class.java))

    @Test
    fun `enabled and unsafe config should fail-fast`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.requireAuthForRelay=false",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("Refusing to start: smtp.relay.enabled=true")
            }
    }

    @Test
    fun `enabled should provide MailRelay and RelayAccessPolicy`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
            )
            .run { context ->
                assertThat(context).hasSingleBean(MailRelay::class.java)
                assertThat(context).hasSingleBean(RelayAccessPolicy::class.java)
            }
    }

    @Test
    fun `enabled should not create DsnSender without DsnStore`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
            )
            .run { context ->
                assertThat(context).doesNotHaveBean(DsnSender::class.java)
            }
    }

    @Test
    fun `enabled should create DsnSender when DsnStore exists`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.hostname=testhost.local",
            )
            .withBean(DsnStore::class.java, java.util.function.Supplier {
                DsnStore { _, _, _, _, _, _, _, _ -> }
            })
            .run { context ->
                assertThat(context).hasSingleBean(DsnSender::class.java)
            }
    }
}
