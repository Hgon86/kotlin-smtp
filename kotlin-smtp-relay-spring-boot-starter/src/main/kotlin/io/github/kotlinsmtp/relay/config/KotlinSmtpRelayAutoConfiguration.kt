package io.github.kotlinsmtp.relay.config

import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessContext
import io.github.kotlinsmtp.relay.api.RelayAccessDecision
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import io.github.kotlinsmtp.relay.api.RelayDeniedReason
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.DsnStore
import io.github.kotlinsmtp.relay.api.RelayRequest
import io.github.kotlinsmtp.relay.api.RelayRoute
import io.github.kotlinsmtp.relay.api.RelayRouteResolver
import io.github.kotlinsmtp.relay.api.OutboundRelayPolicyResolver
import io.github.kotlinsmtp.relay.jakarta.JakartaMailDsnSender
import io.github.kotlinsmtp.relay.jakarta.JakartaMailMxMailRelay
import io.github.kotlinsmtp.relay.jakarta.JakartaMailRoutingMailRelay
import io.github.kotlinsmtp.relay.jakarta.OutboundTlsConfig
import io.github.kotlinsmtp.relay.jakarta.StandardOutboundRelayPolicyResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment

private val log = KotlinLogging.logger {}

@AutoConfiguration(
    beforeName = [
        // Must be applied before default beans of kotlin-smtp-spring-boot-starter.
        // (Prevent @ConditionalOnMissingBean conflicts such as RelayAccessPolicy)
        "io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration",
    ]
)
@ConditionalOnClass(JakartaMailMxMailRelay::class)
@EnableConfigurationProperties(RelayProperties::class)
class KotlinSmtpRelayAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    fun relayGuardrails(props: RelayProperties): RelayGuardrails {
        props.defaultRoute?.toSmartHost()
        props.routes.forEach {
            require(it.domain.isNotBlank()) { "smtp.relay.routes[].domain must not be blank" }
            it.toSmartHost()
        }
        RelayClientCidrMatcher(props.allowedClientCidrs)
        return RelayGuardrails(props)
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun relayRouteResolver(props: RelayProperties): RelayRouteResolver {
        val exactRoutes = linkedMapOf<String, RelayRoute.SmartHost>()
        var wildcardRoute: RelayRoute.SmartHost? = null

        props.routes.forEach { route ->
            val normalizedDomain = route.domain.trim().lowercase()
            val smartHost = route.toSmartHost()
            if (normalizedDomain == "*") {
                wildcardRoute = smartHost
            } else {
                exactRoutes[normalizedDomain] = smartHost
            }
        }
        val defaultRoute = props.defaultRoute?.toSmartHost()

        return RelayRouteResolver { request: RelayRequest ->
            val recipientDomain = request.recipient.substringAfterLast('@', "").trim().lowercase()
            exactRoutes[recipientDomain]
                ?: wildcardRoute
                ?: defaultRoute
                ?: RelayRoute.DirectMx
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun outboundRelayPolicyResolver(props: RelayProperties): OutboundRelayPolicyResolver {
        require(props.outboundPolicy.mtaSts.connectTimeoutMs > 0) {
            "smtp.relay.outboundPolicy.mtaSts.connectTimeoutMs must be > 0"
        }
        require(props.outboundPolicy.mtaSts.readTimeoutMs > 0) {
            "smtp.relay.outboundPolicy.mtaSts.readTimeoutMs must be > 0"
        }
        return StandardOutboundRelayPolicyResolver(
            mtaStsEnabled = props.outboundPolicy.mtaSts.enabled,
            daneEnabled = props.outboundPolicy.dane.enabled,
            mtaStsConnectTimeoutMs = props.outboundPolicy.mtaSts.connectTimeoutMs,
            mtaStsReadTimeoutMs = props.outboundPolicy.mtaSts.readTimeoutMs,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun mailRelay(
        props: RelayProperties,
        routeResolver: RelayRouteResolver,
        outboundRelayPolicyResolverProvider: ObjectProvider<OutboundRelayPolicyResolver>,
    ): MailRelay {
        require(props.outboundTls.connectTimeoutMs > 0) {
            "smtp.relay.outboundTls.connectTimeoutMs must be > 0"
        }
        require(props.outboundTls.readTimeoutMs > 0) {
            "smtp.relay.outboundTls.readTimeoutMs must be > 0"
        }
        val tls = OutboundTlsConfig(
            ports = props.outboundTls.ports,
            startTlsEnabled = props.outboundTls.startTlsEnabled,
            startTlsRequired = props.outboundTls.startTlsRequired,
            checkServerIdentity = props.outboundTls.checkServerIdentity,
            trustAll = props.outboundTls.trustAll,
            trustHosts = props.outboundTls.trustHosts,
            connectTimeoutMs = props.outboundTls.connectTimeoutMs,
            readTimeoutMs = props.outboundTls.readTimeoutMs,
        )
        val policyResolver = outboundRelayPolicyResolverProvider.getIfAvailable()
        return JakartaMailRoutingMailRelay(
            routeResolver = routeResolver,
            mxRelay = JakartaMailMxMailRelay(
                tls = tls,
                outboundPolicyResolver = policyResolver,
            ),
            tls = tls,
            outboundPolicyResolver = policyResolver,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "smtp.relay", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean
    fun relayAccessPolicy(props: RelayProperties): RelayAccessPolicy {
        val cidrMatcher = RelayClientCidrMatcher(props.allowedClientCidrs)
        return RelayAccessPolicy { ctx: RelayAccessContext ->
            if (props.requireAuthForRelay && !ctx.authenticated) {
                return@RelayAccessPolicy RelayAccessDecision.Denied(RelayDeniedReason.AUTH_REQUIRED)
            }

            if (!ctx.authenticated && !cidrMatcher.isAllowed(ctx.peerAddress)) {
                return@RelayAccessPolicy RelayAccessDecision.Denied(
                    RelayDeniedReason.OTHER_POLICY,
                    "Client IP is not in allowedClientCidrs",
                )
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
            if (!props.requireAuthForRelay && props.allowedSenderDomains.isEmpty() && props.allowedClientCidrs.isEmpty()) {
                log.warn {
                    "Outbound relay is configured as OPEN RELAY (requireAuthForRelay=false, no allowedSenderDomains, no allowedClientCidrs). " +
                    "This is insecure for internet-facing servers. " +
                    "Consider enabling requireAuthForRelay or specifying allowedSenderDomains/allowedClientCidrs, " +
                    "or provide a custom RelayAccessPolicy bean for fine-grained control."
                }
            }
            if (props.outboundTls.trustAll) {
                log.warn {
                    "Outbound relay TLS trustAll=true is enabled. This disables certificate validation and is unsafe for production."
                }
            }
            val routesWithTrustAll = props.routes.filter { it.trustAll == true }
            if (routesWithTrustAll.isNotEmpty()) {
                log.warn {
                    "Some relay routes enable trustAll=true: ${routesWithTrustAll.joinToString { it.domain.ifBlank { "<blank-domain>" } }}"
                }
            }
            log.info {
                "Outbound relay enabled (requireAuthForRelay=${props.requireAuthForRelay}, " +
                    "allowedSenderDomains=${props.allowedSenderDomains.size}, allowedClientCidrs=${props.allowedClientCidrs.size})"
            }
        }
    }

    /**
     * Convert Smart Host config-binding object into transfer-route model.
     *
     * @return Smart Host route with validated values
     */
    private fun RelayProperties.SmartHostRouteProperties.toSmartHost(): RelayRoute.SmartHost {
        val normalizedHost = host.trim()
        require(normalizedHost.isNotEmpty()) { "smtp.relay route host must not be blank" }
        require(port in 1..65535) { "smtp.relay route port must be between 1 and 65535, got: $port" }
        return RelayRoute.SmartHost(
            host = normalizedHost,
            port = port,
            username = username.takeIf { it.isNotBlank() },
            password = password.takeIf { it.isNotBlank() },
            startTlsEnabled = startTlsEnabled,
            startTlsRequired = startTlsRequired,
            checkServerIdentity = checkServerIdentity,
            trustAll = trustAll,
            trustHosts = trustHosts,
        )
    }
}
