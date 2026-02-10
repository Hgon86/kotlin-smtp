package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.Values
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 인바운드 입력 폭주 시 Netty autoRead를 토글하여 스로틀링합니다.
 *
 * - 프레임 큐가 가득 차기 전에 읽기를 잠시 멈춰 불필요한 연결 종료를 줄입니다.
 * - BDAT(CHUNKING) 바이트 인플라이트 상한을 포함해 메모리 사용을 제어합니다.
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
        // 입력 라인은 ISO-8859-1로 1:1 바이트 보존을 가정합니다.
        // CRLF는 프레이밍에서 제거되므로 보수적으로 +2만 더합니다.
        return (line.length + 2).toLong()
    }

    fun onQueued(bytes: Long) {
        val current = queuedInboundBytes.addAndGet(bytes)
        if (current >= highWatermarkBytes && autoReadPaused.compareAndSet(false, true)) {
            // STARTTLS 업그레이드 중에는 핸드셰이크 진행을 위해 autoRead 토글에 개입하지 않습니다.
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
