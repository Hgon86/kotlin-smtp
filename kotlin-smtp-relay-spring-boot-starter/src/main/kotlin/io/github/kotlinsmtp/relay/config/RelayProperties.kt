package io.github.kotlinsmtp.relay.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "smtp.relay")
/**
 * Outbound relay configuration bound from the `smtp.relay.*` namespace.
 */
class RelayProperties {
    /**
     * Enables outbound relay support.
     */
    var enabled: Boolean = false

    /**
     * Whether session authentication (AUTH) is required for relay.
     * Default is secure to reduce open-relay risk.
     */
    var requireAuthForRelay: Boolean = true

    /**
     * Sender-domain allowlist for unauthenticated relay exceptions.
     *
     * Empty list disables sender-domain exceptions.
     */
    var allowedSenderDomains: List<String> = emptyList()

    /**
     * Client CIDR allowlist for unauthenticated relay exceptions.
     *
     * Example: `10.0.0.0/8`, `192.168.10.0/24`, `2001:db8::/32`
     *
     * Empty list disables client CIDR exceptions.
     */
    var allowedClientCidrs: List<String> = emptyList()

    /**
     * Default Smart Host route applied to all domains.
     *
     * When null, direct MX delivery is used.
     */
    var defaultRoute: SmartHostRouteProperties? = null

    /**
     * Smart Host routes mapped by recipient domain.
     *
     * `domain` supports exact values like `example.com` and wildcard `*`.
     */
    var routes: List<DomainRouteProperties> = emptyList()

    /**
     * Outbound TLS policy defaults used when route-specific values are not set.
     */
    var outboundTls: OutboundTlsProperties = OutboundTlsProperties()

    /**
     * Outbound TLS policy settings.
     */
    class OutboundTlsProperties {
        /**
         * Outbound ports where TLS policy is applied.
         */
        var ports: List<Int> = listOf(25)

        /**
         * Whether STARTTLS is attempted on outbound relay connections.
         */
        var startTlsEnabled: Boolean = true

        /**
         * Whether STARTTLS is required for outbound relay connections.
         */
        var startTlsRequired: Boolean = false

        /**
         * Whether outbound TLS validates remote server identity.
         */
        var checkServerIdentity: Boolean = true

        /**
         * Trust-all TLS mode for local or test environments.
         */
        var trustAll: Boolean = false

        /**
         * Explicit trusted hostnames for outbound TLS.
         */
        var trustHosts: List<String> = emptyList()

        /**
         * Outbound connection timeout in milliseconds.
         */
        var connectTimeoutMs: Int = 15_000

        /**
         * Outbound read timeout in milliseconds.
         */
        var readTimeoutMs: Int = 15_000
    }

    /**
     * Smart Host route configuration.
     *
     * @property host Target SMTP server host.
     * @property port Target SMTP server port.
     * @property username Optional SMTP AUTH username.
     * @property password Optional SMTP AUTH password.
     * @property startTlsEnabled Optional STARTTLS attempt flag.
     * @property startTlsRequired Optional STARTTLS requirement flag.
     * @property checkServerIdentity Optional server identity verification flag.
     * @property trustAll Optional trust-all mode for local/dev tests.
     * @property trustHosts Optional trusted server host list.
     */
    open class SmartHostRouteProperties {
        /**
         * Target SMTP host.
         */
        var host: String = ""

        /**
         * Target SMTP port.
         */
        var port: Int = 25

        /**
         * Optional SMTP AUTH username.
         */
        var username: String = ""

        /**
         * Optional SMTP AUTH password.
         */
        var password: String = ""

        /**
         * Optional route-specific STARTTLS enable flag.
         */
        var startTlsEnabled: Boolean? = null

        /**
         * Optional route-specific STARTTLS required flag.
         */
        var startTlsRequired: Boolean? = null

        /**
         * Optional route-specific server identity verification flag.
         */
        var checkServerIdentity: Boolean? = null

        /**
         * Optional route-specific trust-all flag for local or test environments.
         */
        var trustAll: Boolean? = null

        /**
         * Optional route-specific trusted hostnames.
         */
        var trustHosts: List<String>? = null
    }

    /**
     * Domain-matched Smart Host route configuration.
     *
     * @property domain Recipient domain to match (for example `govkorea.kr`, `*`).
     */
    class DomainRouteProperties : SmartHostRouteProperties() {
        /**
         * Recipient domain match value. Supports exact domain and `*` wildcard.
         */
        var domain: String = ""
    }
}
