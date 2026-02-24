package io.github.kotlinsmtp.relay.config

import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.DsnStore
import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessContext
import io.github.kotlinsmtp.relay.api.RelayAccessDecision
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import io.github.kotlinsmtp.relay.api.RelayRoute
import io.github.kotlinsmtp.relay.api.RelayRouteResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class KotlinSmtpRelayAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KotlinSmtpRelayAutoConfiguration::class.java))

    @Test
    fun `enabled with open relay config should start with warning`() {
        // Open relay config (`requireAuthForRelay=false`, empty `allowedSenderDomains`)
        // now starts successfully but emits a warning log.
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.requireAuthForRelay=false",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(MailRelay::class.java)
            }
    }

    @Test
    fun `enabled should fail with open relay config when failOnOpenRelay is true`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.requireAuthForRelay=false",
                "smtp.relay.failOnOpenRelay=true",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("smtp.relay.failOnOpenRelay=true blocks open relay configuration")
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
                assertThat(context).hasSingleBean(RelayRouteResolver::class.java)
            }
    }

    @Test
    fun `unauthenticated relay should be denied when client ip is outside allowed cidr`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.requireAuthForRelay=false",
                "smtp.relay.allowedClientCidrs[0]=10.0.0.0/8",
            )
            .run { context ->
                val policy = context.getBean(RelayAccessPolicy::class.java)
                val decision = policy.evaluate(
                    RelayAccessContext(
                        envelopeSender = "user@example.com",
                        recipient = "target@external.test",
                        authenticated = false,
                        peerAddress = "203.0.113.10:2525",
                    ),
                )
                assertThat(decision).isInstanceOf(RelayAccessDecision.Denied::class.java)
            }
    }

    @Test
    fun `enabled should fail when route host is blank`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.routes[0].domain=govkorea.kr",
                "smtp.relay.routes[0].host=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasMessageContaining("smtp.relay route host must not be blank")
            }
    }

    @Test
    fun `enabled should fail when failOnTrustAll and global trustAll are both true`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.outboundTls.failOnTrustAll=true",
                "smtp.relay.outboundTls.trustAll=true",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("failOnTrustAll=true blocks smtp.relay.outboundTls.trustAll=true")
            }
    }

    @Test
    fun `enabled should fail when failOnTrustAll and route trustAll are both true`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.outboundTls.failOnTrustAll=true",
                "smtp.relay.routes[0].domain=example.com",
                "smtp.relay.routes[0].host=smtp.example.com",
                "smtp.relay.routes[0].trustAll=true",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("failOnTrustAll=true blocks smtp.relay.routes[].trustAll=true")
            }
    }

    @Test
    fun `custom route resolver bean should override default`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
            )
            .withBean(RelayRouteResolver::class.java, java.util.function.Supplier {
                RelayRouteResolver { RelayRoute.DirectMx }
            })
            .run { context ->
                assertThat(context).hasSingleBean(RelayRouteResolver::class.java)
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
