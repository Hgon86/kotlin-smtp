package io.github.kotlinsmtp.server

import io.netty.channel.Channel
import io.netty.handler.ssl.SslHandler
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * STARTTLS 업그레이드 전환(읽기 차단, 게이트, SslHandler 삽입, 핸드셰이크)을 담당합니다.
 *
 * - begin(): pipelining 방지 및 전환 준비
 * - complete(): 220 flush 이후 호출되어야 하며, 핸드셰이크 성공 시 onHandshakeSuccess를 실행합니다.
 */
internal class SmtpTlsUpgradeManager(
    private val channel: Channel,
    private val server: SmtpServer,
    private val incomingFrames: KChannel<SmtpInboundFrame>,
    private val tlsUpgrading: AtomicBoolean,
    private val setAutoReadOnEventLoop: suspend (Boolean) -> Unit,
    private val onFrameDiscarded: (SmtpInboundFrame) -> Unit,
) {
    suspend fun begin(): Boolean {
        if (!tlsUpgrading.compareAndSet(false, true)) return false

        // 읽기 중단(autoRead=false)은 event loop에서 수행해야 안전합니다.
        setAutoReadOnEventLoop(false)

        // autoRead=false 전환 직후에도 이미 스케줄된 read가 수행될 수 있으므로,
        // SslHandler가 삽입되기 전까지는 raw bytes가 디코더로 흘러가지 않도록 게이트를 둡니다.
        addStartTlsInboundGateOnEventLoop()

        // STARTTLS는 파이프라이닝할 수 없습니다. 이미 큐에 남은 입력이 있으면(=대기 커맨드/데이터)
        // 프로토콜 동기화를 위해 업그레이드를 거부하는 쪽이 안전합니다.
        val hasPending = drainPendingInboundFrames()
        if (hasPending) {
            tlsUpgrading.set(false)
            return false
        }
        return true
    }

    suspend fun complete(onHandshakeSuccess: suspend () -> Unit) {
        val sslContext = server.sslContext ?: return

        try {
            val sslHandler = addSslHandlerOnEventLoop(sslContext)

            // 게이트가 버퍼링한 raw bytes가 있으면(클라이언트가 매우 빨리 ClientHello를 보내는 경우)
            // SslHandler가 먼저 처리할 수 있도록 게이트 제거 후 pipeline head에서 재주입합니다.
            removeStartTlsInboundGateAndReplayOnEventLoop()

            // TLS 핸드셰이크 바이트를 읽기 위해 읽기를 재개합니다.
            setAutoReadOnEventLoop(true)

            awaitHandshake(sslHandler)
            onHandshakeSuccess()
        } finally {
            tlsUpgrading.set(false)
        }
    }

    private suspend fun addSslHandlerOnEventLoop(sslContext: io.netty.handler.ssl.SslContext): SslHandler =
        suspendCancellableCoroutine { cont ->
            channel.eventLoop().execute {
                val pipeline = channel.pipeline()

                val existing = pipeline.get("ssl") as? SslHandler
                if (existing != null) {
                    cont.resume(existing)
                    return@execute
                }

                val sslHandler = sslContext.newHandler(channel.alloc()).also {
                    it.engine().useClientMode = false
                    it.setHandshakeTimeout(server.tlsHandshakeTimeout, TimeUnit.MILLISECONDS)
                }

                pipeline.addFirst("ssl", sslHandler)
                cont.resume(sslHandler)
            }
        }

    private suspend fun awaitHandshake(sslHandler: SslHandler) {
        suspendCancellableCoroutine<Unit> { cont ->
            sslHandler.handshakeFuture().addListener { future ->
                if (future.isSuccess) cont.resume(Unit) else cont.resumeWithException(future.cause())
            }
        }
    }

    private suspend fun addStartTlsInboundGateOnEventLoop() {
        suspendCancellableCoroutine<Unit> { cont ->
            channel.eventLoop().execute {
                val pipeline = channel.pipeline()
                if (pipeline.get(STARTTLS_GATE_NAME) == null) {
                    pipeline.addFirst(STARTTLS_GATE_NAME, StartTlsInboundGate())
                }
                cont.resume(Unit)
            }
        }
    }

    private suspend fun removeStartTlsInboundGateAndReplayOnEventLoop() {
        suspendCancellableCoroutine<Unit> { cont ->
            channel.eventLoop().execute {
                val pipeline = channel.pipeline()
                val gate = pipeline.get(STARTTLS_GATE_NAME) as? StartTlsInboundGate
                if (gate == null) {
                    cont.resume(Unit)
                    return@execute
                }

                val buffered = gate.drain()
                runCatching { pipeline.remove(STARTTLS_GATE_NAME) }

                // pipeline.fireChannelRead는 head에서 시작하므로, ssl 핸들러가 있으면 ssl이 먼저 처리합니다.
                for (msg in buffered) {
                    pipeline.fireChannelRead(msg)
                }
                if (buffered.isNotEmpty()) {
                    pipeline.fireChannelReadComplete()
                }
                cont.resume(Unit)
            }
        }
    }

    private fun drainPendingInboundFrames(): Boolean {
        var drained = false
        while (true) {
            val frame = incomingFrames.tryReceive().getOrNull() ?: break
            drained = true
            onFrameDiscarded(frame)
        }
        return drained
    }
}
