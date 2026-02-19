package io.github.kotlinsmtp.spool

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val triggerLog = KotlinLogging.logger {}

/**
 * Serially executes external spool triggers through a coalescing queue.
 *
 * @property scope execution scope
 * @property runOnce callback for single trigger execution
 */
internal class SpoolTriggerDispatcher(
    private val scope: CoroutineScope,
    private val runOnce: suspend (String?) -> Unit,
) {
    private val triggerStateLock = Any()
    private var triggerDrainerRunning = false
    private val triggerCoalescer = TriggerCoalescer()

    /**
     * Registers a trigger request.
     *
     * @param targetDomain null for full queue, value to run only that domain
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
     * Drains and executes queued triggers sequentially.
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
