package io.github.kotlinsmtp.metrics

import io.github.kotlinsmtp.spi.SmtpEventHook
import io.github.kotlinsmtp.spi.SmtpMessageAcceptedEvent
import io.github.kotlinsmtp.spi.SmtpMessageRejectedEvent
import io.github.kotlinsmtp.spi.SmtpSessionEndedEvent
import io.github.kotlinsmtp.spi.SmtpSessionStartedEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicInteger

/**
 * Default hook that bridges core SMTP events into Micrometer metrics.
 *
 * @property registry Registry where metrics are registered
 */
class MicrometerSmtpEventHook(
    private val registry: MeterRegistry,
) : SmtpEventHook {
    private val activeSessions = AtomicInteger(0)
    private val sessionStartedCounter: Counter = Counter.builder("smtp.sessions.started.total")
        .description("Total started SMTP sessions")
        .register(registry)
    private val sessionEndedCounter: Counter = Counter.builder("smtp.sessions.ended.total")
        .description("Total ended SMTP sessions")
        .register(registry)
    private val acceptedCounter: Counter = Counter.builder("smtp.messages.accepted.total")
        .description("Total accepted SMTP messages")
        .register(registry)
    private val rejectedCounter: Counter = Counter.builder("smtp.messages.rejected.total")
        .description("Total rejected SMTP messages")
        .register(registry)

    init {
        Gauge.builder("smtp.connections.active", activeSessions) { it.get().toDouble() }
            .description("Current active SMTP sessions")
            .register(registry)
    }

    override suspend fun onSessionStarted(event: SmtpSessionStartedEvent) {
        sessionStartedCounter.increment()
        activeSessions.incrementAndGet()
    }

    override suspend fun onSessionEnded(event: SmtpSessionEndedEvent) {
        sessionEndedCounter.increment()
        activeSessions.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    override suspend fun onMessageAccepted(event: SmtpMessageAcceptedEvent) {
        acceptedCounter.increment()
    }

    override suspend fun onMessageRejected(event: SmtpMessageRejectedEvent) {
        rejectedCounter.increment()
    }
}
