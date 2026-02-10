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
 * 코어 SMTP 이벤트를 Micrometer 메트릭으로 브릿지하는 기본 훅입니다.
 *
 * @property registry 메트릭 등록 대상 레지스트리
 */
class MicrometerSmtpEventHook(
    private val registry: MeterRegistry,
) : SmtpEventHook {
    private val activeSessions = AtomicInteger(0)
    private val sessionStartedCounter: Counter = Counter.builder("smtp.sessions.started.total")
        .description("시작된 SMTP 세션 누적 수")
        .register(registry)
    private val sessionEndedCounter: Counter = Counter.builder("smtp.sessions.ended.total")
        .description("종료된 SMTP 세션 누적 수")
        .register(registry)
    private val acceptedCounter: Counter = Counter.builder("smtp.messages.accepted.total")
        .description("수락된 SMTP 메시지 누적 수")
        .register(registry)
    private val rejectedCounter: Counter = Counter.builder("smtp.messages.rejected.total")
        .description("거부된 SMTP 메시지 누적 수")
        .register(registry)

    init {
        Gauge.builder("smtp.connections.active", activeSessions) { it.get().toDouble() }
            .description("현재 활성 SMTP 세션 수")
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
