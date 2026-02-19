package io.github.kotlinsmtp.routing

/**
 * Local/external routing policy SPI for inbound mail.
 *
 * Default implementation is config-file based,
 * and can be replaced with DB or external service integration.
 */
public fun interface InboundRoutingPolicy {

    /**
     * Determine whether the given recipient belongs to a local domain.
     *
     * @param recipient Recipient email address (e.g., user@example.com)
     * @return true if local domain, false if external domain
     */
    public fun isLocalDomain(recipient: String): Boolean

    /**
     * Return currently managed local-domain list.
     * (used for logging, monitoring, HELLO responses, etc.)
     *
     * @return Set of local domains
     */
    public fun localDomains(): Set<String> = emptySet()
}

/**
 * Simple single-domain routing policy.
 *
 * @property domain Local domain (e.g., example.com)
 */
public class SingleDomainRoutingPolicy(
    private val domain: String
) : InboundRoutingPolicy {

    private val normalizedDomain = domain.trim().lowercase()

    override fun isLocalDomain(recipient: String): Boolean {
        val recipientDomain = recipient.substringAfterLast('@', "").trim().lowercase()
        return recipientDomain == normalizedDomain
    }

    override fun localDomains(): Set<String> = setOf(normalizedDomain)
}

/**
 * Multi-domain routing policy.
 *
 * @property domains List of local domains
 */
public class MultiDomainRoutingPolicy(
    private val domains: Set<String>
) : InboundRoutingPolicy {

    private val normalizedDomains = domains.map { it.trim().lowercase() }.toSet()

    override fun isLocalDomain(recipient: String): Boolean {
        val recipientDomain = recipient.substringAfterLast('@', "").trim().lowercase()
        return recipientDomain in normalizedDomains
    }

    override fun localDomains(): Set<String> = normalizedDomains
}
