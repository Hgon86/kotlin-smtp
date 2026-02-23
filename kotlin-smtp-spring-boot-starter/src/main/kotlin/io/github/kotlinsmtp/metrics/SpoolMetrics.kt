package io.github.kotlinsmtp.metrics

/**
 * Boundary for recording operational metrics in spool processing path.
 *
 * @property NOOP Default implementation used when metrics are disabled
 */
interface SpoolMetrics {
    /**
     * Synchronize initial pending message count.
     *
     * @param count Current pending message count in spool directory
     */
    fun initializePending(count: Long)

    /** Record that one message was enqueued into spool queue. */
    fun onQueued()

    /** Record that a message was removed from spool queue after successful completion. */
    fun onCompleted()

    /** Record that a message was removed from spool queue (e.g., retry-limit exceeded). */
    fun onDropped()

    /**
     * Record delivery-attempt results.
     *
     * @param deliveredCount Number of successful recipients
     * @param transientFailureCount Number of transient-failure recipients
     * @param permanentFailureCount Number of permanent-failure recipients
     */
    fun onDeliveryResults(deliveredCount: Int, transientFailureCount: Int, permanentFailureCount: Int)

    /** Record that retry scheduling occurred. */
    fun onRetryScheduled(delaySeconds: Long = 0)

    /**
     * Record queue residence time when a message leaves spool.
     *
     * @param outcome final status (`completed` or `dropped`)
     * @param queueAgeSeconds message age in spool queue
     */
    fun onFinalized(outcome: String, queueAgeSeconds: Long)

    /**
     * Record recipient-level failures with coarse reason taxonomy.
     *
     * @param domain recipient domain
     * @param permanent permanent/transient flag
     * @param reasonClass coarse reason class
     */
    fun onRecipientFailure(domain: String, permanent: Boolean, reasonClass: String)

    companion object {
        /**
         * No-op implementation used when metrics are not configured.
         *
         * @return Implementation that performs no action
         */
        val NOOP: SpoolMetrics = object : SpoolMetrics {
            override fun initializePending(count: Long): Unit = Unit
            override fun onQueued(): Unit = Unit
            override fun onCompleted(): Unit = Unit
            override fun onDropped(): Unit = Unit
            override fun onDeliveryResults(deliveredCount: Int, transientFailureCount: Int, permanentFailureCount: Int): Unit = Unit
            override fun onRetryScheduled(delaySeconds: Long): Unit = Unit
            override fun onFinalized(outcome: String, queueAgeSeconds: Long): Unit = Unit
            override fun onRecipientFailure(domain: String, permanent: Boolean, reasonClass: String): Unit = Unit
        }
    }
}
