package io.github.kotlinsmtp.relay.config

import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessContext
import io.github.kotlinsmtp.relay.api.RelayAccessDecision
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import io.github.kotlinsmtp.relay.api.RelayDeniedReason
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.DsnStore
import io.github.kotlinsmtp.relay.jakarta.JakartaMailDsnSender
import io.github.kotlinsmtp.relay.jakarta.JakartaMailMxMailRelay
import io.github.kotlinsmtp.relay.jakarta.OutboundTlsConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

private val log = KotlinLogging.logger {}

@AutoConfiguration(
    beforeName = [
        // kotlin-smtp-spring-boot-starter의 기본 빈보다 먼저 적용되어야 합니다.
        // (RelayAccessPolicy 등 @ConditionalOnMissingBean 충돌을 방지)
        "io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration",
    ]
)
@ConditionalOnClass(JakartaMailMxMailRelay::class)
@EnableConfigurationProperties(RelayProperties::class)
class KotlinSmtpRelayAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    fun relayGuardrails(props: RelayProperties): RelayGuardrails = RelayGuardrails(props)

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun mailRelay(props: RelayProperties): MailRelay =
        JakartaMailMxMailRelay(
            tls = OutboundTlsConfig(
                ports = props.outboundTls.ports,
                startTlsEnabled = props.outboundTls.startTlsEnabled,
                startTlsRequired = props.outboundTls.startTlsRequired,
                checkServerIdentity = props.outboundTls.checkServerIdentity,
                trustAll = props.outboundTls.trustAll,
                trustHosts = props.outboundTls.trustHosts,
                connectTimeoutMs = props.outboundTls.connectTimeoutMs,
                readTimeoutMs = props.outboundTls.readTimeoutMs,
            )
        )

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun relayAccessPolicy(props: RelayProperties): RelayAccessPolicy = RelayAccessPolicy { ctx: RelayAccessContext ->
        if (props.requireAuthForRelay && !ctx.authenticated) {
            return@RelayAccessPolicy RelayAccessDecision.Denied(RelayDeniedReason.AUTH_REQUIRED)
        }
        if (props.allowedSenderDomains.isEmpty()) {
            return@RelayAccessPolicy RelayAccessDecision.Allowed
        }
        val domain = ctx.envelopeSender?.substringAfterLast('@')?.lowercase()
        if (!ctx.authenticated && (domain == null || props.allowedSenderDomains.none { it.equals(domain, ignoreCase = true) })) {
            return@RelayAccessPolicy RelayAccessDecision.Denied(RelayDeniedReason.SENDER_DOMAIN_NOT_ALLOWED)
        }
        RelayAccessDecision.Allowed
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(DsnStore::class)
    @ConditionalOnMissingBean
    fun dsnSender(env: Environment, store: DsnStore): DsnSender {
        val hostname = env.getProperty("smtp.hostname")?.takeIf { it.isNotBlank() } ?: "localhost"
        return JakartaMailDsnSender(serverHostname = hostname, store = store)
    }

    class RelayGuardrails(props: RelayProperties) {
        init {
            if (!props.requireAuthForRelay && props.allowedSenderDomains.isEmpty()) {
                error("Refusing to start: smtp.relay.enabled=true without smtp.relay.requireAuthForRelay=true or smtp.relay.allowedSenderDomains allowlist")
            }
            log.info { "Outbound relay enabled (requireAuthForRelay=${props.requireAuthForRelay}, allowedSenderDomains=${props.allowedSenderDomains.size})" }
        }
    }
}
