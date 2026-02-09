package io.github.kotlinsmtp.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Micrometer 기반 스풀 메트릭 구현입니다.
 *
 * @property registry 메트릭 등록 대상 레지스트리
 */
class MicrometerSpoolMetrics(
    private val registry: MeterRegistry,
) : SpoolMetrics {
    private val pendingMessages = AtomicLong(0)
    private val queuedCounter: Counter = Counter.builder("smtp.spool.queued.total")
        .description("스풀 큐에 적재된 메시지 누적 수")
        .register(registry)
    private val completedCounter: Counter = Counter.builder("smtp.spool.completed.total")
        .description("스풀 큐에서 정상 완료로 제거된 메시지 누적 수")
        .register(registry)
    private val droppedCounter: Counter = Counter.builder("smtp.spool.dropped.total")
        .description("스풀 큐에서 드롭된 메시지 누적 수")
        .register(registry)
    private val retryScheduledCounter: Counter = Counter.builder("smtp.spool.retry.scheduled.total")
        .description("스풀 재시도 스케줄링 누적 수")
        .register(registry)
    private val deliveredRecipientsCounter: Counter = Counter.builder("smtp.spool.delivery.recipients.total")
        .description("스풀 전달 성공 수신자 누적 수")
        .tag("result", "delivered")
        .register(registry)
    private val transientFailureRecipientsCounter: Counter = Counter.builder("smtp.spool.delivery.recipients.total")
        .description("스풀 전달 실패 수신자 누적 수")
        .tag("result", "transient_failure")
        .register(registry)
    private val permanentFailureRecipientsCounter: Counter = Counter.builder("smtp.spool.delivery.recipients.total")
        .description("스풀 전달 실패 수신자 누적 수")
        .tag("result", "permanent_failure")
        .register(registry)

    init {
        Gauge.builder("smtp.spool.pending", pendingMessages) { it.get().toDouble() }
            .description("현재 스풀 대기 메시지 수")
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
