package io.github.kotlinsmtp.relay.api

/**
 * Failure classification for outbound relay.
 *
 * Implementations classify failures as transient/permanent so spooler/retry policies can decide.
 */
public sealed class RelayException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    public abstract val isTransient: Boolean

    /** RFC 3463 Enhanced Status Code (e.g., 4.4.1, 5.7.1). */
    public open val enhancedStatusCode: String? = null

    /** Remote server response (when available). */
    public open val remoteReply: String? = null
}

public class RelayTransientException(
    message: String,
    cause: Throwable? = null,
    override val enhancedStatusCode: String? = null,
    override val remoteReply: String? = null,
) : RelayException(message, cause) {
    override val isTransient: Boolean = true
}

public class RelayPermanentException(
    message: String,
    cause: Throwable? = null,
    override val enhancedStatusCode: String? = null,
    override val remoteReply: String? = null,
) : RelayException(message, cause) {
    override val isTransient: Boolean = false
}
