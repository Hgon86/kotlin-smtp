package com.crinity.kotlinsmtp.server

import com.crinity.kotlinsmtp.protocol.handler.SmtpChannelHandler
import com.crinity.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import com.crinity.kotlinsmtp.protocol.handler.SmtpUserHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.CharsetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private val log = KotlinLogging.logger {}

@Suppress("MemberVisibilityCanBePrivate")
class SmtpServer(
    val port: Int,
    val hostname: String = InetAddress.getLocalHost().canonicalHostName,
    val serviceName: String? = "kotlin-smtp",
    val transactionHandlerCreator: (() -> SmtpProtocolHandler)? = null,
    val userHandler: SmtpUserHandler? = null,
    private val certChainFile: File? = null,
    private val privateKeyFile: File? = null
) {
    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverMutex = Mutex()
    private var channelFuture: ChannelFuture? = null
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    val sslContext: SslContext? by lazy {
        if (certChainFile != null && privateKeyFile != null) {
            try {
                SslContextBuilder.forServer(certChainFile, privateKeyFile).build()
            } catch (e: Exception) {
                log.error(e) { "Failed to initialize SSL context" }
                null
            }
        } else null
    }

    suspend fun start(
        coroutineContext: CoroutineContext = Dispatchers.IO,
        wait: Boolean = false
    ): Boolean {
        return serverMutex.withLock {
            if (channelFuture == null) {
                val bootstrap = ServerBootstrap()
                bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addLast(
                                LineBasedFrameDecoder(8192),
                                StringDecoder(CharsetUtil.UTF_8),
                                StringEncoder(CharsetUtil.UTF_8),
                                IdleStateHandler(0, 0, 5, TimeUnit.MINUTES), // 5분 타임아웃
                                SmtpChannelHandler(this@SmtpServer)
                            )
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128) // 수락되지 않은 연결 요청을 얼마나 큐에 담을지
                    .childOption(ChannelOption.SO_KEEPALIVE, true) // TCP keepalive 활성화

                channelFuture = bootstrap.bind(port).sync()

                log.info { "Started smtp server, now listening on port $port" }

                if (wait) {
                    serverScope.launch(coroutineContext) {
                        channelFuture?.channel()?.closeFuture()?.sync()
                    }
                }
                true
            } else false
        }
    }

    suspend fun stop(): Boolean = serverMutex.withLock {
        if (channelFuture != null) {
            try {
                serverScope.cancel()
                workerGroup.shutdownGracefully()
                bossGroup.shutdownGracefully()
                channelFuture?.channel()?.close()?.sync()
                true
            } finally {
                channelFuture = null
            }
        } else false
    }
}