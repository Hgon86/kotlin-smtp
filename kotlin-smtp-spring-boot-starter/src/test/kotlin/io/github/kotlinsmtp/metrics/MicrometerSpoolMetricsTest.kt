package io.github.kotlinsmtp.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicrometerSpoolMetricsTest {

    /**
     * Verifies spool metrics implementation consistently updates pending gauge and counters.
     */
    @Test
    fun `spool metrics track pending and counters`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerSpoolMetrics(registry)

        metrics.initializePending(2)
        metrics.onQueued()
        metrics.onDeliveryResults(deliveredCount = 2, transientFailureCount = 1, permanentFailureCount = 0)
        metrics.onRetryScheduled()
        metrics.onCompleted()
        metrics.onDropped()

        assertEquals(1.0, registry.get("smtp.spool.pending").gauge().value())
        assertEquals(1.0, registry.get("smtp.spool.queued.total").counter().count())
        assertEquals(1.0, registry.get("smtp.spool.completed.total").counter().count())
        assertEquals(1.0, registry.get("smtp.spool.dropped.total").counter().count())
        assertEquals(1.0, registry.get("smtp.spool.retry.scheduled.total").counter().count())
        assertEquals(
            2.0,
            registry.get("smtp.spool.delivery.recipients.total").tag("result", "delivered").counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("smtp.spool.delivery.recipients.total").tag("result", "transient_failure").counter().count(),
        )
    }
}
