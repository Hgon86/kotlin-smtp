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
     * `start(wait=true)` must not return until server channel is closed.
     */
    @Test
    fun `start wait blocks until stop`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = SmtpServer.create(port, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }
            listener.enableStartTls = false
            listener.enableAuth = false
            proxyProtocol.enabled = false
        }

        // Netty bind/closeFuture waits are blocking calls (sync), so run them on IO.
        val job = launch(Dispatchers.IO) { server.start(wait = true) }

        // Verify start(wait=true) is still active and not completed immediately.
        delay(200)
        assertTrue(job.isActive, "Expected start(wait=true) to still be running")

        // Verify actual bind by reading server banner.
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
     * Port should be immediately re-bindable once `stop()` returns.
     */
    @Test
    fun `stop releases port`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = SmtpServer.create(port, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }
            listener.enableStartTls = false
            listener.enableAuth = false
            proxyProtocol.enabled = false
        }

        server.start()
        server.stop(gracefulTimeoutMs = 5_000)

        ServerSocket(port).use { }
    }

    /**
     * Same server instance should support repeated start/stop cycles.
     */
    @Test
    fun `server can restart after stop`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = SmtpServer.create(port, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }
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
