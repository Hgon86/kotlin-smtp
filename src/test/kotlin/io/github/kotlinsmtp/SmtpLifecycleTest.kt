package io.github.kotlinsmtp

import io.github.kotlinsmtp.server.SmtpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SmtpLifecycleTest {

    /**
     * `start(wait=true)`는 서버 채널이 닫힐 때까지 반환하지 않아야 합니다.
     */
    @Test
    fun `start wait blocks until stop`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = SmtpServer.create(port, "test-smtp.local") {
            serviceName = "test-smtp"
            useProtocolHandlerFactory { TestSmtpProtocolHandler() }
            listener.enableStartTls = false
            listener.enableAuth = false
            proxyProtocol.enabled = false
        }

        // Netty bind/closeFuture waits are blocking calls (sync), so run them on IO.
        val job = launch(Dispatchers.IO) { server.start(wait = true) }

        // start(wait=true)가 즉시 완료되지 않고 살아있는지 확인
        delay(200)
        assertTrue(job.isActive, "Expected start(wait=true) to still be running")

        // 실제로 바인딩 되었는지 확인(배너 수신)
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 2_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val greeting = reader.readLine()
            assertTrue(greeting.startsWith("220"), "Expected 220 greeting, got: $greeting")
        }

        server.stop(gracefulTimeoutMs = 5_000)
        withTimeout(5.seconds) { job.join() }
    }

    /**
     * `stop()`이 반환되면 포트가 즉시 재바인딩 가능해야 합니다.
     */
    @Test
    fun `stop releases port`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = SmtpServer.create(port, "test-smtp.local") {
            serviceName = "test-smtp"
            useProtocolHandlerFactory { TestSmtpProtocolHandler() }
            listener.enableStartTls = false
            listener.enableAuth = false
            proxyProtocol.enabled = false
        }

        server.start()
        server.stop(gracefulTimeoutMs = 5_000)

        ServerSocket(port).use { }
    }

    /**
     * 동일 인스턴스에 대해 start/stop을 반복할 수 있어야 합니다.
     */
    @Test
    fun `server can restart after stop`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = SmtpServer.create(port, "test-smtp.local") {
            serviceName = "test-smtp"
            useProtocolHandlerFactory { TestSmtpProtocolHandler() }
            listener.enableStartTls = false
            listener.enableAuth = false
            proxyProtocol.enabled = false
        }

        server.start()
        server.stop(gracefulTimeoutMs = 5_000)

        server.start()
        Socket("localhost", port).use { socket ->
            socket.soTimeout = 2_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val greeting = reader.readLine()
            assertTrue(greeting.startsWith("220"), "Expected 220 greeting after restart, got: $greeting")
        }

        server.stop(gracefulTimeoutMs = 5_000)
    }
}
