package io.github.kotlinsmtp.server

/**
 * Collection of server feature flags.
 *
 * @property enableVrfy Enable VRFY command
 * @property enableEtrn Enable ETRN command
 * @property enableExpn Enable EXPN command
 */
public class SmtpFeatureFlags {
    public var enableVrfy: Boolean = false
    public var enableEtrn: Boolean = false
    public var enableExpn: Boolean = false
}

/**
 * Per-listener (port) policy.
 *
 * @property implicitTls Whether TLS starts immediately on connect (SMTPS/465)
 * @property enableStartTls Whether STARTTLS is supported
 * @property enableAuth Whether AUTH command/advertisement is allowed
 * @property requireAuthForMail Whether AUTH is required before starting MAIL transaction
 * @property idleTimeoutSeconds Connection idle timeout (seconds). 0 means no timeout (default: 300s = 5min)
 */
public class SmtpListenerPolicy {
    public var implicitTls: Boolean = false
    public var enableStartTls: Boolean = true
    public var enableAuth: Boolean = true
    public var requireAuthForMail: Boolean = false
    public var idleTimeoutSeconds: Int = 300
}

/**
 * PROXY protocol (v1) configuration.
 *
 * @property enabled Whether PROXY protocol is accepted
 * @property trustedProxyCidrs Trusted proxy CIDR list
 */
public class SmtpProxyProtocolPolicy {
    public var enabled: Boolean = false
    public var trustedProxyCidrs: List<String> = listOf("127.0.0.1/32", "::1/128")
}

/**
 * TLS configuration.
 *
 * @property certChainPath Certificate chain path
 * @property privateKeyPath Private key path
 * @property minTlsVersion Minimum TLS version
 * @property handshakeTimeoutMs TLS handshake timeout (ms)
 * @property cipherSuites Allowed cipher suites (when specified)
 */
public class SmtpTlsPolicy {
    public var certChainPath: java.nio.file.Path? = null
    public var privateKeyPath: java.nio.file.Path? = null
    public var minTlsVersion: String = "TLSv1.2"
    public var handshakeTimeoutMs: Int = 30_000
    public var cipherSuites: List<String> = emptyList()
}

/**
 * Connection/message rate-limit configuration.
 *
 * @property maxConnectionsPerIp Maximum concurrent connections per IP
 * @property maxMessagesPerIpPerHour Maximum messages per hour per IP
 */
public class SmtpRateLimitPolicy {
    public var maxConnectionsPerIp: Int = 10
    public var maxMessagesPerIpPerHour: Int = 100
}

/**
 * Authentication (AUTH) rate-limit configuration.
 *
 * @property enabled Whether auth rate limit is enabled
 * @property maxFailuresPerWindow Maximum failures within window
 * @property windowSeconds Window size (seconds)
 * @property lockoutDurationSeconds Lockout duration (seconds)
 */
public class SmtpAuthRateLimitPolicy {
    public var enabled: Boolean = true
    public var maxFailuresPerWindow: Int = 5
    public var windowSeconds: Long = 300
    public var lockoutDurationSeconds: Long = 600
}
