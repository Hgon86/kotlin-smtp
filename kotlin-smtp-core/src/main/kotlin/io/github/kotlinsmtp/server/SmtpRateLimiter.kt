package io.github.kotlinsmtp.server

/**
 * Defines connection/message rate-limit operations used by SMTP sessions.
 */
public interface SmtpRateLimiter {
    /**
     * Checks whether a new connection is allowed for the client IP.
     */
    public fun allowConnection(ipAddress: String): Boolean

    /**
     * Releases one active connection slot for the client IP.
     */
    public fun releaseConnection(ipAddress: String)

    /**
     * Checks whether accepting one more message is allowed for the client IP.
     */
    public fun allowMessage(ipAddress: String): Boolean

    /**
     * Runs periodic cleanup for stale limiter state.
     */
    public fun cleanup()
}
