package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.CharsetUtil
import java.util.concurrent.TimeUnit

/**
 * Responsible for configuring SMTP server channel pipeline.
 *
 * @property server Server instance referenced for pipeline policy
 * @property tlsHandshakeTimeoutMs TLS handshake timeout (ms)
 * @property idleTimeoutSeconds Connection idle timeout (seconds)
 */
internal class SmtpServerPipelineConfigurator(
    private val server: SmtpServer,
    private val tlsHandshakeTimeoutMs: Int,
    private val idleTimeoutSeconds: Int,
) {
    /**
     * Configure socket channel pipeline.
     *
     * @param channel Channel to initialize
     */
    fun configure(channel: SocketChannel) {
        val pipeline = channel.pipeline()

        if (server.proxyProtocolEnabled) {
            pipeline.addLast("proxyDecoder", HAProxyMessageDecoder())
        }

        if (server.implicitTls) {
            val sslContext = server.sslContext
            if (sslContext == null) {
                channel.close()
                return
            }

            val sslHandler = sslContext.newHandler(channel.alloc()).also {
                it.engine().useClientMode = false
                it.setHandshakeTimeout(tlsHandshakeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
            pipeline.addLast("ssl", sslHandler)
        }

        pipeline.addLast("smtpInboundDecoder", SmtpInboundDecoder())
        pipeline.addLast("stringEncoder", StringEncoder(CharsetUtil.ISO_8859_1))

        if (idleTimeoutSeconds > 0) {
            pipeline.addLast(
                "idleStateHandler",
                IdleStateHandler(0, 0, idleTimeoutSeconds.toLong(), TimeUnit.SECONDS),
            )
        }

        pipeline.addLast("smtpChannelHandler", SmtpChannelHandler(server))
    }
}
