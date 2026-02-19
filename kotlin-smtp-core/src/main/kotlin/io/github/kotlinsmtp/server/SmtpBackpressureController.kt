package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.Values
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Throttles by toggling Netty autoRead when inbound input bursts.
 *
 * - Temporarily pauses reads before frame queue fills up, reducing unnecessary connection closures.
 * - Controls memory usage including in-flight byte cap for BDAT (CHUNKING).
 */
internal class SmtpBackpressureController(
    private val scope: CoroutineScope,
    private val isTlsUpgrading: () -> Boolean,
    private val setAutoRead: suspend (Boolean) -> Unit,
    private val highWatermarkBytes: Long = 512L * 1024L,
    private val lowWatermarkBytes: Long = 128L * 1024L,
) {
    private val queuedInboundBytes = AtomicLong(0)
    private val autoReadPaused = AtomicBoolean(false)
    private val inflightBdatBytes = AtomicLong(0)

    fun estimateLineBytes(line: String): Long {
        // Assume input lines preserve bytes 1:1 in ISO-8859-1.
        // CRLF is removed during framing, so conservatively add +2.
        return (line.length + 2).toLong()
    }

    fun onQueued(bytes: Long) {
        val current = queuedInboundBytes.addAndGet(bytes)
        if (current >= highWatermarkBytes && autoReadPaused.compareAndSet(false, true)) {
            // Do not intervene in autoRead toggling during STARTTLS upgrade to allow handshake progression.
            if (!isTlsUpgrading()) {
                scope.launch { setAutoRead(false) }
            }
        }
    }

    fun onConsumed(bytes: Long) {
        val current = queuedInboundBytes.addAndGet(-bytes)
        if (current <= lowWatermarkBytes && autoReadPaused.compareAndSet(true, false)) {
            if (!isTlsUpgrading()) {
                scope.launch { setAutoRead(true) }
            }
        }
    }

    fun tryReserveInflightBdatBytes(bytes: Int): Boolean {
        if (bytes <= 0) return true
        while (true) {
            val current = inflightBdatBytes.get()
            val next = current + bytes
            if (next > Values.MAX_INFLIGHT_BDAT_BYTES) return false
            if (inflightBdatBytes.compareAndSet(current, next)) return true
        }
    }

    fun releaseInflightBdatBytes(bytes: Int) {
        if (bytes <= 0) return
        inflightBdatBytes.addAndGet(-bytes.toLong())
    }
}
