package io.github.kotlinsmtp.relay.config

import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.DsnStore
import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessContext
import io.github.kotlinsmtp.relay.api.RelayAccessDecision
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import io.github.kotlinsmtp.relay.api.RelayAccessPolicyRule
import io.github.kotlinsmtp.relay.api.RelayDeniedReason
import io.github.kotlinsmtp.relay.api.RelayRoute
import io.github.kotlinsmtp.relay.api.RelayRouteResolver
import io.github.kotlinsmtp.relay.api.RelayRouteRule
import io.github.kotlinsmtp.relay.api.RelayRequest
import io.github.kotlinsmtp.relay.api.Rfc822Source
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import java.io.ByteArrayInputStream

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
    fun `ordered RelayRouteRule should resolve before default route`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.defaultRoute.host=default.smtp.local",
                "smtp.relay.defaultRoute.port=2525",
            )
            .withUserConfiguration(RouteRuleTestConfig::class.java)
            .run { context ->
                val resolver = context.getBean(RelayRouteResolver::class.java)

                val specialRoute = resolver.resolve(
                    relayRequest(recipient = "rcpt@priority.example"),
                )
                val fallbackRoute = resolver.resolve(
                    relayRequest(recipient = "rcpt@other.example"),
                )

                assertThat(specialRoute)
                    .isEqualTo(RelayRoute.SmartHost(host = "priority.smtp.local", port = 2526))
                assertThat(fallbackRoute)
                    .isEqualTo(RelayRoute.SmartHost(host = "default.smtp.local", port = 2525))
            }
    }

    @Test
    fun `ordered RelayAccessPolicyRule should be applied before default policy`() {
        contextRunner
            .withPropertyValues(
                "smtp.relay.enabled=true",
                "smtp.relay.requireAuthForRelay=false",
            )
            .withUserConfiguration(AccessRuleTestConfig::class.java)
            .run { context ->
                val policy = context.getBean(RelayAccessPolicy::class.java)

                val denied = policy.evaluate(
                    RelayAccessContext(
                        envelopeSender = "sender@test.local",
                        recipient = "user@blocked.example",
                        authenticated = true,
                        peerAddress = "127.0.0.1:2525",
                    ),
                )
                val allowed = policy.evaluate(
                    RelayAccessContext(
                        envelopeSender = "sender@test.local",
                        recipient = "user@ok.example",
                        authenticated = true,
                        peerAddress = "127.0.0.1:2525",
                    ),
                )

                assertThat(denied)
                    .isEqualTo(RelayAccessDecision.Denied(RelayDeniedReason.OTHER_POLICY, "Recipient blocked by rule"))
                assertThat(allowed).isEqualTo(RelayAccessDecision.Allowed)
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

    @Configuration(proxyBeanMethods = false)
    class RouteRuleTestConfig {
        @Bean
        @Order(100)
        fun priorityRouteRule(): RelayRouteRule = RelayRouteRule { request ->
            val domain = request.recipient.substringAfterLast('@', "").lowercase()
            if (domain == "priority.example") {
                RelayRoute.SmartHost(host = "priority.smtp.local", port = 2526)
            } else {
                null
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class AccessRuleTestConfig {
        @Bean
        @Order(100)
        fun blockRecipientRule(): RelayAccessPolicyRule = RelayAccessPolicyRule { context ->
            val domain = context.recipient.substringAfterLast('@', "").lowercase()
            if (domain == "blocked.example") {
                RelayAccessDecision.Denied(RelayDeniedReason.OTHER_POLICY, "Recipient blocked by rule")
            } else {
                null
            }
        }
    }

    private fun relayRequest(recipient: String): RelayRequest = RelayRequest(
        messageId = "test-message",
        envelopeSender = "sender@test.local",
        recipient = recipient,
        authenticated = true,
        rfc822 = Rfc822Source { ByteArrayInputStream("Subject: test\r\n\r\nbody".toByteArray(Charsets.US_ASCII)) },
    )
}
