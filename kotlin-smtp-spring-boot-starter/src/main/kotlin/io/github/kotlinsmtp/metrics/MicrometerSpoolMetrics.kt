package io.github.kotlinsmtp.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Micrometer-based spool metrics implementation.
 *
 * @property registry Registry where metrics are registered
 */
class MicrometerSpoolMetrics(
    private val registry: MeterRegistry,
) : SpoolMetrics {
    private val pendingMessages = AtomicLong(0)
    private val queuedCounter: Counter = Counter.builder("smtp.spool.queued.total")
        .description("Total messages enqueued in spool queue")
        .register(registry)
    private val completedCounter: Counter = Counter.builder("smtp.spool.completed.total")
        .description("Total messages removed from spool queue after successful completion")
        .register(registry)
    private val droppedCounter: Counter = Counter.builder("smtp.spool.dropped.total")
        .description("Total messages dropped from spool queue")
        .register(registry)
    private val retryScheduledCounter: Counter = Counter.builder("smtp.spool.retry.scheduled.total")
        .description("Total spool retry scheduling events")
        .register(registry)
    private val deliveredRecipientsCounter: Counter = Counter.builder("smtp.spool.delivery.recipients.total")
        .description("Total successfully delivered recipients from spool")
        .tag("result", "delivered")
        .register(registry)
    private val transientFailureRecipientsCounter: Counter = Counter.builder("smtp.spool.delivery.recipients.total")
        .description("Total transient-failure recipients in spool delivery")
        .tag("result", "transient_failure")
        .register(registry)
    private val permanentFailureRecipientsCounter: Counter = Counter.builder("smtp.spool.delivery.recipients.total")
        .description("Total permanent-failure recipients in spool delivery")
        .tag("result", "permanent_failure")
        .register(registry)

    init {
        Gauge.builder("smtp.spool.pending", pendingMessages) { it.get().toDouble() }
            .description("Current pending messages in spool")
            .register(registry)
    }

    override fun initializePending(count: Long) {
        pendingMessages.set(count.coerceAtLeast(0))
    }

    override fun onQueued() {
        queuedCounter.increment()
        pendingMessages.incrementAndGet()
    }

    override fun onCompleted() {
        completedCounter.increment()
        pendingMessages.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    override fun onDropped() {
        droppedCounter.increment()
        pendingMessages.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    override fun onDeliveryResults(deliveredCount: Int, transientFailureCount: Int, permanentFailureCount: Int) {
        if (deliveredCount > 0) deliveredRecipientsCounter.increment(deliveredCount.toDouble())
        if (transientFailureCount > 0) transientFailureRecipientsCounter.increment(transientFailureCount.toDouble())
        if (permanentFailureCount > 0) permanentFailureRecipientsCounter.increment(permanentFailureCount.toDouble())
    }

    override fun onRetryScheduled() {
        retryScheduledCounter.increment()
    }
}
