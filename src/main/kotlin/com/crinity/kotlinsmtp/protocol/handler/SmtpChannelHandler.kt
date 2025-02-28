package com.crinity.kotlinsmtp.protocol.handler

import com.crinity.kotlinsmtp.server.SmtpServer
import com.crinity.kotlinsmtp.server.SmtpSession
import com.crinity.kotlinsmtp.utils.SmtpStatusCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

class SmtpChannelHandler(private val server: SmtpServer) : ChannelInboundHandlerAdapter() {
    private lateinit var session: SmtpSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun channelActive(ctx: ChannelHandlerContext) {
        session = SmtpSession(ctx.channel(), server)
        scope.launch {
            session.handle()

        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is String) {  // 이제 String으로 디코딩된 메시지를 받습니다
            scope.launch {
                session.handleIncomingLine(msg)
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        scope.cancel()
        session.close()
    }

    @Deprecated("Deprecated in Java")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error(cause) { "Error in SMTP session" }
        scope.cancel()
        session.close()
        ctx.close()
    }

    /// 타임아웃 이벤트 처리
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            scope.launch {
                session.sendResponse(
                    SmtpStatusCode.ERROR_IN_PROCESSING.code,
                    "Timeout waiting for client input. Closing connection."
                )
                log.info { "Connection idle timeout - closing channel" }
                ctx.close()
            }
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }
}