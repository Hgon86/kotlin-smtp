package io.github.kotlinsmtp.server

/**
 * Result of spool trigger request.
 */
public enum class SpoolTriggerResult {
    /** Trigger request accepted successfully. */
    ACCEPTED,

    /** Rejected due to invalid request argument. */
    INVALID_ARGUMENT,

    /** Spooler is currently unable to process the request. */
    UNAVAILABLE,
}

/**
 * Minimal hook for triggering spool/delivery processing.
 *
 * - Core does not include storage/relay implementation; host provides implementation.
 * - `tryTriggerOnce` follows a non-blocking contract; actual processing may run in async worker.
 */
public interface SmtpSpooler {
    /**
     * Trigger spool processing once immediately.
     *
     * @return Request acceptance status
     */
    public fun tryTriggerOnce(): SpoolTriggerResult = runCatching {
        triggerOnce()
        SpoolTriggerResult.ACCEPTED
    }.getOrElse {
        SpoolTriggerResult.UNAVAILABLE
    }

    /**
     * Legacy-compatible trigger method.
     */
    public fun triggerOnce(): Unit
}

/**
 * Spooler extension hook that can reflect ETRN domain argument.
 */
public interface SmtpDomainSpooler : SmtpSpooler {
    /**
     * Trigger spool processing once immediately for the specified domain.
     *
     * @param domain Normalized ASCII domain from ETRN argument
     */
    public fun tryTriggerOnce(domain: String): SpoolTriggerResult = runCatching {
        triggerOnce(domain)
        SpoolTriggerResult.ACCEPTED
    }.getOrElse {
        SpoolTriggerResult.UNAVAILABLE
    }

    /**
     * Legacy-compatible domain trigger method.
     *
     * @param domain Normalized ASCII domain from ETRN argument
     */
    public fun triggerOnce(domain: String): Unit
}
