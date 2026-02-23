package io.github.kotlinsmtp.auth

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRateLimiterTest {

    @Test
    fun lockIsAppliedAfterConfiguredFailures() {
        val limiter = AuthRateLimiter(
            maxFailuresPerWindow = 3,
            windowSeconds = 300,
            lockoutDurationSeconds = 60,
        )

        assertTrue(!limiter.recordFailure("10.0.0.1", "user"))
        assertTrue(!limiter.recordFailure("10.0.0.1", "user"))
        assertTrue(limiter.recordFailure("10.0.0.1", "user"))

        val remaining = limiter.checkLock("10.0.0.1", "user")
        assertNotNull(remaining)
        assertTrue(remaining > 0)
    }

    @Test
    fun recordSuccessClearsExistingFailureState() {
        val limiter = AuthRateLimiter(
            maxFailuresPerWindow = 2,
            windowSeconds = 300,
            lockoutDurationSeconds = 60,
        )

        limiter.recordFailure("10.0.0.1", "user")
        limiter.recordFailure("10.0.0.1", "user")
        assertNotNull(limiter.checkLock("10.0.0.1", "user"))

        limiter.recordSuccess("10.0.0.1", "user")
        assertNull(limiter.checkLock("10.0.0.1", "user"))
    }
}
