package io.github.kotlinsmtp.relay.api

/**
 * Policy hints for outbound relay security posture.
 *
 * Typical sources are MTA-STS/DANE or custom policy engines.
 * This model intentionally stays generic and pluggable.
 *
 * @property requireTls whether TLS is required for delivery
 * @property requireValidCertificate whether server identity/certificate validation is required
 * @property source optional policy source identifier (for logging)
 */
public data class OutboundRelayPolicy(
    public val requireTls: Boolean = false,
    public val requireValidCertificate: Boolean = false,
    public val source: String? = null,
)

/**
 * Resolves outbound relay policy for recipient domains.
 *
 * Implementations may use MTA-STS, DANE, static allowlists, or external services.
 */
public fun interface OutboundRelayPolicyResolver {
    /**
     * Returns policy for recipient domain, or null when no explicit policy exists.
     *
     * @param recipientDomain normalized recipient domain
     * @return resolved outbound policy, or null
     */
    public fun resolve(recipientDomain: String): OutboundRelayPolicy?
}
