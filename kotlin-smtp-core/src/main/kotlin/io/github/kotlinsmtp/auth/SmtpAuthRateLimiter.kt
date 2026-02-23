package io.github.kotlinsmtp.auth

/**
 * Defines shared AUTH failure tracking and lock control.
 */
public interface SmtpAuthRateLimiter {
    /**
     * Checks lock status before authentication attempt.
     *
     * @return remaining lock seconds when locked, otherwise null
     */
    public fun checkLock(clientIp: String?, username: String): Long?

    /**
     * Records one authentication failure.
     *
     * @return true when lock is newly active
     */
    public fun recordFailure(clientIp: String?, username: String): Boolean

    /**
     * Resets failure record after successful authentication.
     */
    public fun recordSuccess(clientIp: String?, username: String)

    /**
     * Runs periodic cleanup for stale limiter state.
     */
    public fun cleanup()
}
