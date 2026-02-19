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
 * Handles STARTTLS upgrade transition (read blocking, gate, SslHandler insertion, handshake).
 *
 * - begin(): prevent pipelining and prepare transition
 * - complete(): must be called after 220 flush; executes onHandshakeSuccess on successful handshake.
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

        // Disabling reads (autoRead=false) should be performed on event loop for safety.
        setAutoReadOnEventLoop(false)

        // Even right after switching autoRead=false, already scheduled reads may still run,
        // so place a gate to block raw bytes from reaching decoder before SslHandler is inserted.
        addStartTlsInboundGateOnEventLoop()

        // STARTTLS cannot be pipelined. If queued input already exists (pending command/data),
        // rejecting upgrade is safer for protocol synchronization.
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

            // If gate buffered raw bytes (e.g., client sends ClientHello very quickly),
            // remove gate and replay from pipeline head so SslHandler processes first.
            removeStartTlsInboundGateAndReplayOnEventLoop()

            // Resume reads to consume TLS handshake bytes.
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

                // pipeline.fireChannelRead starts at head, so ssl handler processes first when present.
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
