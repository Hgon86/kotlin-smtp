package io.github.kotlinsmtp.spool

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val triggerLog = KotlinLogging.logger {}

/**
 * 외부 스풀 트리거를 coalescing 큐로 직렬 실행합니다.
 *
 * @property scope 실행 스코프
 * @property runOnce 단일 트리거 실행 콜백
 */
internal class SpoolTriggerDispatcher(
    private val scope: CoroutineScope,
    private val runOnce: suspend (String?) -> Unit,
) {
    private val triggerStateLock = Any()
    private var triggerDrainerRunning = false
    private val triggerCoalescer = TriggerCoalescer()

    /**
     * 트리거를 등록합니다.
     *
     * @param targetDomain null이면 전체 큐, 값이 있으면 해당 도메인만 실행
     */
    fun submit(targetDomain: String?) {
        val shouldStartDrainer = synchronized(triggerStateLock) {
            triggerCoalescer.submit(targetDomain)
            if (triggerDrainerRunning) {
                false
            } else {
                triggerDrainerRunning = true
                true
            }
        }

        if (shouldStartDrainer) {
            scope.launch { drainPendingTriggers() }
        }
    }

    /**
     * 적재된 트리거를 순차 실행합니다.
     */
    private suspend fun drainPendingTriggers() {
        while (true) {
            val nextTarget = synchronized(triggerStateLock) {
                when (val next = triggerCoalescer.poll()) {
                    is SpoolTrigger.Full -> null
                    is SpoolTrigger.Domain -> next.domain
                    null -> {
                        triggerDrainerRunning = false
                        return
                    }
                }
            }

            runCatching { runOnce(nextTarget) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (nextTarget == null) {
                        triggerLog.warn(error) { "Spooler triggerOnce failed" }
                    } else {
                        triggerLog.warn(error) { "Spooler triggerOnce(domain) failed domain=$nextTarget" }
                    }
                }
        }
    }
}
