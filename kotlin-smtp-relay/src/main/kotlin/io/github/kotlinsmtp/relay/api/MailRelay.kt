package io.github.kotlinsmtp.relay.api

import java.io.InputStream

/**
 * Relay RFC822 raw message to external domain.
 */
public interface MailRelay {
    public suspend fun relay(request: RelayRequest): RelayResult
}

/**
 * Minimal input for relay invocation (raw message + envelope + metadata).
 *
 * @property messageId Internal tracking identifier (used in logs/DSN, etc.)
 * @property envelopeSender MAIL FROM (reverse-path). Use null/blank to represent bounce (<>).
 * @property recipient RCPT TO (single recipient). Fan-out is handled by caller.
 * @property authenticated Whether session is authenticated (for policy/logging)
 * @property rfc822 RFC822 raw source
 */
public data class RelayRequest(
    public val messageId: String,
    public val envelopeSender: String?,
    public val recipient: String,
    public val authenticated: Boolean,
    public val rfc822: Rfc822Source,
)

/**
 * RFC822 raw provider.
 */
public fun interface Rfc822Source {
    public fun openStream(): InputStream
}

/**
 * Relay success result (minimal fields).
 */
public data class RelayResult(
    public val remoteHost: String? = null,
    public val remotePort: Int? = null,
    public val serverGreeting: String? = null,
)
