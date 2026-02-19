package io.github.kotlinsmtp.server

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil

internal const val STARTTLS_GATE_NAME: String = "startTlsGate"

/**
 * Temporary gate to prevent raw bytes received before SslHandler insertion during STARTTLS upgrade
 * from flowing into SMTP decoder and breaking protocol synchronization.
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

            // Retain for later replay, and consume current read.
            buffered.add(ReferenceCountUtil.retain(msg))
            ReferenceCountUtil.release(msg)
            return
        }

        // Unexpected type likely means broken session/protocol sync; close connection.
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
        // Leak prevention: if removed without drain, release all buffered messages.
        for (msg in buffered) {
            ReferenceCountUtil.release(msg)
        }
        buffered.clear()
        bufferedBytes = 0
    }
}
