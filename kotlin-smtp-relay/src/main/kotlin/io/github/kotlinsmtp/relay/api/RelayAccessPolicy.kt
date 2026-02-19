package io.github.kotlinsmtp.relay.api

/**
 * Relay allow/deny policy (primary boundary for open-relay prevention).
 */
public fun interface RelayAccessPolicy {
    public fun evaluate(context: RelayAccessContext): RelayAccessDecision
}

/**
 * @property envelopeSender MAIL FROM (reverse-path)
 * @property recipient RCPT TO
 * @property authenticated Whether session is authenticated
 * @property peerAddress Client address (e.g., `203.0.113.10:587`, `[2001:db8::1]:587`)
 */
public data class RelayAccessContext(
    public val envelopeSender: String?,
    public val recipient: String,
    public val authenticated: Boolean,
    public val peerAddress: String? = null,
) {
    /**
     * Preserves binary compatibility for the legacy 3-parameter constructor.
     *
     * @param envelopeSender MAIL FROM (reverse-path)
     * @param recipient RCPT TO
     * @param authenticated Whether session is authenticated
     */
    public constructor(
        envelopeSender: String?,
        recipient: String,
        authenticated: Boolean,
    ) : this(
        envelopeSender = envelopeSender,
        recipient = recipient,
        authenticated = authenticated,
        peerAddress = null,
    )
}

public sealed interface RelayAccessDecision {
    public data object Allowed : RelayAccessDecision

    /**
     * @property reason Standardized denial reason
     * @property message Short description for operations/logging (recommend minimizing sensitive data)
     */
    public data class Denied(
        public val reason: RelayDeniedReason,
        public val message: String? = null,
    ) : RelayAccessDecision
}

public enum class RelayDeniedReason {
    AUTH_REQUIRED,
    SENDER_DOMAIN_NOT_ALLOWED,
    OTHER_POLICY,
}
