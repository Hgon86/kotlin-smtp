package io.github.kotlinsmtp.config

import io.github.kotlinsmtp.storage.SentArchiveMode
import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties(prefix = "smtp")
/**
 * Root SMTP server configuration bound from the `smtp.*` namespace.
 */
class SmtpServerProperties {
    /**
     * SMTP listening port used when `smtp.listeners` is not configured.
     */
    var port: Int = 25

    /**
     * Hostname advertised in SMTP banners and protocol responses.
     */
    var hostname: String = "localhost"

    /**
     * Default service banner name used by listeners.
     */
    var serviceName: String = "kotlin-smtp"

    /**
     * Inbound TLS settings for STARTTLS and implicit TLS listeners.
     */
    var ssl: SslConfig = SslConfig()

    /**
     * Storage path settings for mailbox, temp files, and mailing lists.
     */
    var storage: StorageConfig = StorageConfig()

    /**
     * Local-domain routing settings used to split local delivery and relay paths.
     */
    var routing: RoutingConfig = RoutingConfig()

    /**
     * Legacy relay-related settings kept for compatibility.
     */
    var relay: RelayConfig = RelayConfig()

    /**
     * Spool queue settings for retryable outbound delivery.
     */
    var spool: SpoolConfig = SpoolConfig()

    /**
     * Sent mailbox archiving policy settings.
     */
    var sentArchive: SentArchiveConfig = SentArchiveConfig()

    /**
     * SMTP AUTH settings.
     */
    var auth: AuthConfig = AuthConfig()

    /**
     * Connection and message rate-limit settings.
     */
    var rateLimit: RateLimitConfig = RateLimitConfig()

    /**
     * Protocol feature flags such as VRFY, ETRN, and EXPN.
     */
    var features: FeaturesConfig = FeaturesConfig()

    /**
     * Trusted proxy network ranges for PROXY protocol.
     */
    var proxy: ProxyConfig = ProxyConfig()

    /**
     * Multi-listener definitions. When non-empty, this overrides single-port mode.
     */
    var listeners: List<ListenerConfig> = emptyList()

    /**
     * Storage path configuration.
     */
    data class StorageConfig(
        /**
         * Base directory for local mailbox storage.
         */
        var mailboxDir: String = "",

        /**
         * Directory for temporary files used during message processing.
         */
        var tempDir: String = "",

        /**
         * Directory for local mailing-list files used by EXPN handling.
         */
        var listsDir: String = "",
    ) {
        val mailboxPath: Path get() = Path.of(mailboxDir)
        val tempPath: Path get() = Path.of(tempDir)
        val listsPath: Path get() = Path.of(listsDir)

        fun validate() {
            require(mailboxDir.isNotBlank()) {
                "smtp.storage.mailboxDir must be configured (e.g., ./data/mailboxes or /var/smtp/mailboxes)"
            }
            require(tempDir.isNotBlank()) {
                "smtp.storage.tempDir must be configured (e.g., ./data/temp or /var/smtp/temp)"
            }
            require(listsDir.isNotBlank()) {
                "smtp.storage.listsDir must be configured (e.g., ./data/lists or /var/smtp/lists)"
            }
        }
    }

    /**
     * Determines local-domain routing (local delivery vs external relay).
     *
     * @property localDomain Legacy single local domain used for local delivery checks.
     * @property localDomains Preferred multi-domain list used for local delivery checks.
     */
    data class RoutingConfig(
        /**
         * Legacy single local domain used when `localDomains` is empty.
         */
        var localDomain: String = "",

        /**
         * Preferred local domain list for local delivery checks.
         */
        var localDomains: List<String> = emptyList(),
    ) {
        /**
         * Returns effective local domains.
         * Falls back to [localDomain] when [localDomains] is empty for legacy compatibility.
         */
        fun effectiveLocalDomains(): Set<String> {
            return if (localDomains.isNotEmpty()) {
                localDomains.map { it.trim().lowercase() }.toSet()
            } else {
                val single = localDomain.trim()
                if (single.isNotEmpty()) setOf(single.lowercase()) else emptySet()
            }
        }
    }

    data class RelayConfig(
        /**
         * Enables relay flow in the core starter when relay modules are present.
         */
        var enabled: Boolean = false,
        /**
         * Legacy key: `smtp.relay.localDomain`.
         *
         * Prefer `smtp.routing.localDomain` in new configurations.
         * Kept only for compatibility and used as fallback when `smtp.routing.localDomain` is blank.
         */
        @Deprecated("Use smtp.routing.localDomain instead")
        var localDomain: String = "",
    )

    @Suppress("DEPRECATION")
    fun effectiveLocalDomain(): String {
        val r = routing.localDomain.trim()
        if (r.isNotEmpty()) return r
        return relay.localDomain.trim()
    }

    data class SpoolConfig(
        /**
         * Spool backend type.
         */
        var type: SpoolType = SpoolType.AUTO,

        /**
         * Spool directory path.
         */
        var dir: String = "",

        /**
         * Maximum retry attempts for transient failures.
         */
        var maxRetries: Int = 5,

        /**
         * Initial retry delay in seconds before backoff applies.
         */
        var retryDelaySeconds: Long = 60,

        /**
         * Redis-specific spool settings.
         */
        var redis: RedisConfig = RedisConfig(),
    ) {
        enum class SpoolType {
            AUTO,
            FILE,
            REDIS,
        }

        data class RedisConfig(
            /**
             * Redis key prefix for spool data.
             */
            var keyPrefix: String = "kotlin-smtp:spool",

            /**
             * Maximum RFC822 raw payload bytes allowed in Redis spool.
             */
            var maxRawBytes: Long = 25L * 1024L * 1024L,

            /**
             * Redis lock TTL in seconds for spool processing.
             */
            var lockTtlSeconds: Long = 900,
        )

        val path: Path get() = Path.of(dir)

        fun validate() {
            require(dir.isNotBlank()) {
                "smtp.spool.dir must be configured (e.g., ./data/spool or /var/smtp/spool)"
            }
            require(maxRetries >= 0) {
                "smtp.spool.maxRetries must be >= 0"
            }
            require(retryDelaySeconds > 0) {
                "smtp.spool.retryDelaySeconds must be > 0"
            }
            if (type == SpoolType.REDIS) {
                require(redis.keyPrefix.isNotBlank()) {
                    "smtp.spool.redis.keyPrefix must not be blank when smtp.spool.type=redis"
                }
                require(redis.maxRawBytes > 0) {
                    "smtp.spool.redis.maxRawBytes must be > 0 when smtp.spool.type=redis"
                }
                require(redis.lockTtlSeconds > 0) {
                    "smtp.spool.redis.lockTtlSeconds must be > 0 when smtp.spool.type=redis"
                }
            }
        }
    }

    /**
     * Sent mailbox archiving policy.
     *
     * @property mode Trigger policy for sent mailbox archiving.
     */
    data class SentArchiveConfig(
        /**
         * Trigger policy for sent mailbox archiving.
         */
        var mode: SentArchiveMode = SentArchiveMode.TRUSTED_SUBMISSION,
    )

    /**
     * SMTP AUTH configuration.
     */
    data class AuthConfig(
        /**
         * Enables SMTP AUTH.
         */
        var enabled: Boolean = false,

        /**
         * Requires AUTH before mail transactions.
         */
        var required: Boolean = false,

        /**
         * Static username/password map for the default in-memory auth service.
         */
        var users: Map<String, String> = emptyMap(),

        /**
         * Enables AUTH failure rate limiting.
         */
        var rateLimitEnabled: Boolean = true,

        /**
         * Maximum failed AUTH attempts allowed within the tracking window.
         */
        var rateLimitMaxFailures: Int = 5,

        /**
         * AUTH failure tracking window in seconds.
         */
        var rateLimitWindowSeconds: Long = 300,

        /**
         * AUTH lockout duration in seconds after threshold is exceeded.
         */
        var rateLimitLockoutSeconds: Long = 600,
    )

    /**
     * Generic connection and message throughput limits.
     */
    data class RateLimitConfig(
        /**
         * Maximum concurrent connections allowed per client IP.
         */
        var maxConnectionsPerIp: Int = 10,

        /**
         * Maximum accepted messages per client IP per hour.
         */
        var maxMessagesPerIpPerHour: Int = 100,
    )

    /**
     * Trusted proxy ranges when PROXY protocol(v1) is enabled.
     *
     * PROXY headers are spoofable, so only trusted proxy sources (LB/HAProxy) must be allowed.
     * The default trusts loopback only; add production proxy source IP/CIDRs explicitly.
     *
     * @property trustedCidrs Trusted proxy IP/CIDR list.
     */
    data class ProxyConfig(
        /**
         * Trusted proxy source IP/CIDR list allowed to send PROXY protocol headers.
         */
        var trustedCidrs: List<String> = listOf("127.0.0.1/32", "::1/128"),
    )

    /**
     * Feature flags default to conservative off for internet-exposed deployments.
     * Enable only when required (especially VRFY/ETRN) with proper access control.
     *
     * @property vrfyEnabled Enables the VRFY command.
     * @property etrnEnabled Enables the ETRN command.
     * @property expnEnabled Enables the EXPN command.
     */
    data class FeaturesConfig(
        /**
         * Enables VRFY command support.
         */
        var vrfyEnabled: Boolean = false,

        /**
         * Enables ETRN command support.
         */
        var etrnEnabled: Boolean = false,

        /**
         * Enables EXPN command support.
         */
        var expnEnabled: Boolean = false,
    )

    /**
     * Listener-level policy separation by port.
     *
     * Typical patterns:
     * - MTA (25): optional AUTH, opportunistic STARTTLS
     * - Submission (587): STARTTLS + AUTH required
     * - SMTPS (465): implicit TLS + AUTH required
     *
     * @property port Listener port. `0` lets the OS allocate an available port.
     * @property serviceName Listener-specific service name. Uses `smtp.serviceName` when null.
     * @property implicitTls Whether TLS starts immediately on connect.
     * @property enableStartTls Whether STARTTLS is supported.
     * @property enableAuth Whether AUTH is supported.
     * @property requireAuthForMail Whether AUTH is required before MAIL FROM.
     * @property proxyProtocol Whether PROXY protocol(v1) is accepted on this listener.
     * @property idleTimeoutSeconds Connection idle timeout in seconds. `0` disables timeout.
     */
    data class ListenerConfig(
        /**
         * Listener port. `0` lets the OS allocate an available port.
         */
        var port: Int = 25,

        /**
         * Listener-specific service name. When null, `smtp.serviceName` is used.
         */
        var serviceName: String? = null,

        /**
         * Whether TLS starts immediately after connection (implicit TLS / SMTPS).
         */
        var implicitTls: Boolean = false,

        /**
         * Whether STARTTLS is supported on this listener.
         */
        var enableStartTls: Boolean = true,

        /**
         * Whether AUTH is supported on this listener.
         */
        var enableAuth: Boolean = true,

        /**
         * Whether AUTH is required before MAIL FROM on this listener.
         */
        var requireAuthForMail: Boolean = false,

        /**
         * Whether PROXY protocol(v1) is accepted on this listener.
         */
        var proxyProtocol: Boolean = false,

        /**
         * Connection idle timeout in seconds. `0` disables timeout.
         */
        var idleTimeoutSeconds: Int = 300,
    ) {
        fun validate() {
            // Port 0 lets the system allocate an available port (commonly for tests)
            require(port in 0..65535) {
                "Listener port must be between 0 and 65535, got: $port"
            }
            require(idleTimeoutSeconds >= 0) {
                "Listener idleTimeoutSeconds must be >= 0, got: $idleTimeoutSeconds"
            }
        }
    }

    /**
     * Validates the full property set.
     * Called from `KotlinSmtpAutoConfiguration.smtpServers()`.
     */
    fun validate() {
        // Validate required storage/spool paths
        storage.validate()
        spool.validate()

        // Validate listener settings
        listeners.forEach { listener ->
            listener.validate()
        }

        // Validate single-port mode range as well
        // Port 0 lets the system allocate an available port (commonly for tests)
        if (listeners.isEmpty()) {
            require(port in 0..65535) {
                "smtp.port must be between 0 and 65535, got: $port"
            }
        }

        // Validate local domains
        val localDomains = routing.effectiveLocalDomains()
        require(localDomains.isNotEmpty()) {
            "smtp.routing.localDomain(s) must be configured (e.g., mydomain.com)"
        }

        // Validate SSL/TLS settings
        ssl.validate()

        // Validate rate limit settings
        require(rateLimit.maxConnectionsPerIp > 0) {
            "smtp.rateLimit.maxConnectionsPerIp must be > 0"
        }
        require(rateLimit.maxMessagesPerIpPerHour > 0) {
            "smtp.rateLimit.maxMessagesPerIpPerHour must be > 0"
        }

        // Validate AUTH rate limit settings
        if (auth.rateLimitEnabled) {
            require(auth.rateLimitMaxFailures > 0) {
                "smtp.auth.rateLimitMaxFailures must be > 0"
            }
            require(auth.rateLimitWindowSeconds > 0) {
                "smtp.auth.rateLimitWindowSeconds must be > 0"
            }
            require(auth.rateLimitLockoutSeconds > 0) {
                "smtp.auth.rateLimitLockoutSeconds must be > 0"
            }
        }
    }
}
