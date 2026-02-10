package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.CharsetUtil
import java.util.concurrent.TimeUnit

/**
 * SMTP 서버 채널 파이프라인 구성을 담당합니다.
 *
 * @property server 파이프라인 정책을 참조할 서버 인스턴스
 * @property tlsHandshakeTimeoutMs TLS 핸드셰이크 타임아웃(ms)
 * @property idleTimeoutSeconds 연결 유휴 타임아웃(초)
 */
internal class SmtpServerPipelineConfigurator(
    private val server: SmtpServer,
    private val tlsHandshakeTimeoutMs: Int,
    private val idleTimeoutSeconds: Int,
) {
    /**
     * 소켓 채널 파이프라인을 구성합니다.
     *
     * @param channel 초기화 대상 채널
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
