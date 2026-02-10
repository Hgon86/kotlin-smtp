package io.github.kotlinsmtp.server

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil

internal const val STARTTLS_GATE_NAME: String = "startTlsGate"

/**
 * STARTTLS 업그레이드 전환 구간에서 SslHandler 삽입 전에 들어온 raw bytes가
 * SMTP 디코더로 흘러가 프로토콜이 깨지는 것을 방지하기 위한 임시 게이트입니다.
 */
internal class StartTlsInboundGate : ChannelInboundHandlerAdapter() {
    private val buffered: MutableList<Any> = ArrayList(2)
    private var bufferedBytes: Long = 0
    private val maxBufferedBytes: Long = 512L * 1024L

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf) {
            bufferedBytes += msg.readableBytes().toLong()
            if (bufferedBytes > maxBufferedBytes) {
                ReferenceCountUtil.release(msg)
                ctx.close()
                return
            }

            // 이후 재주입을 위해 retain한 뒤, 현재 read는 소비합니다.
            buffered.add(ReferenceCountUtil.retain(msg))
            ReferenceCountUtil.release(msg)
            return
        }

        // 예상치 못한 타입은 세션/프로토콜 동기화가 깨졌을 가능성이 높아 종료합니다.
        ReferenceCountUtil.release(msg)
        ctx.close()
    }

    fun drain(): List<Any> {
        if (buffered.isEmpty()) return emptyList()
        val copy = buffered.toList()
        buffered.clear()
        bufferedBytes = 0
        return copy
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        // 누수 방지: 제거되는데도 drain되지 않았다면 모두 해제합니다.
        for (msg in buffered) {
            ReferenceCountUtil.release(msg)
        }
        buffered.clear()
        bufferedBytes = 0
    }
}
