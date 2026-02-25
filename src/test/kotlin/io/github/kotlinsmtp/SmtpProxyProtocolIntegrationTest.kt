package io.github.kotlinsmtp

import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.spi.SmtpEventHook
import io.github.kotlinsmtp.spi.SmtpSessionStartedEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.assertTrue

// shared SMTP test helpers (skipEhloResponse, etc.)

class SmtpProxyProtocolIntegrationTest {

    /**
     * Trusted PROXY source should be accepted and peerAddress should use the PROXY source endpoint.
     */
    @Test
    fun `trusted proxy header is accepted and peer address is overridden`() = runBlocking {
        val testPort = ServerSocket(0).use { it.localPort }
        var capturedPeerAddress: String? = null

        val server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }
            addEventHook(object : SmtpEventHook {
                override suspend fun onSessionStarted(event: SmtpSessionStartedEvent) {
                    capturedPeerAddress = event.context.peerAddress
                }
            })

            listener.enableStartTls = false
            listener.enableAuth = false
            listener.implicitTls = false

            proxyProtocol.enabled = true
            proxyProtocol.trustedProxyCidrs = listOf("127.0.0.1/32")
        }

        server.start()
        Thread.sleep(100)

        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 3_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()

                out.write("PROXY TCP4 198.51.100.10 203.0.113.5 45678 25\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val greeting = reader.readLine()
                assertTrue(greeting.startsWith("220"), "Expected 220 greeting after trusted PROXY header, got: $greeting")

                out.write("EHLO test.client.local\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                reader.skipEhloResponse()

                out.write("QUIT\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                reader.readLine()
            }

            assertTrue(
                capturedPeerAddress == "198.51.100.10:45678",
                "Expected peerAddress from PROXY source, got: $capturedPeerAddress",
            )
        } finally {
            server.stop(gracefulTimeoutMs = 5_000)
        }
    }

    /**
     * Untrusted PROXY source should be rejected and connection should close without greeting.
     */
    @Test
    fun `untrusted proxy header is rejected`() = runBlocking {
        val testPort = ServerSocket(0).use { it.localPort }

        val server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }

            listener.enableStartTls = false
            listener.enableAuth = false
            listener.implicitTls = false

            proxyProtocol.enabled = true
            proxyProtocol.trustedProxyCidrs = listOf("10.0.0.0/8")
        }

        server.start()
        Thread.sleep(100)

        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 1_500
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()

                out.write("PROXY TCP4 198.51.100.10 203.0.113.5 45678 25\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val closed = try {
                    val line = reader.readLine()
                    line == null
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: Exception) {
                    true
                }

                assertTrue(closed, "Expected connection close for untrusted PROXY source")
            }
        } finally {
            server.stop(gracefulTimeoutMs = 5_000)
        }
    }

    /**
     * Even trusted proxy connections should be closed when PROXY header has unsupported UNKNOWN family.
     */
    @Test
    fun `trusted proxy unknown family is rejected`() = runBlocking {
        val testPort = ServerSocket(0).use { it.localPort }

        val server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }

            listener.enableStartTls = false
            listener.enableAuth = false
            listener.implicitTls = false

            proxyProtocol.enabled = true
            proxyProtocol.trustedProxyCidrs = listOf("127.0.0.1/32")
        }

        server.start()
        Thread.sleep(100)

        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 1_500
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()

                out.write("PROXY UNKNOWN\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val closed = try {
                    val line = reader.readLine()
                    line == null
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: Exception) {
                    true
                }

                assertTrue(closed, "Expected connection close for PROXY UNKNOWN header")
            }
        } finally {
            server.stop(gracefulTimeoutMs = 5_000)
        }
    }

    /**
     * Trusted proxy connections should be closed when source port in PROXY header is invalid.
     */
    @Test
    fun `trusted proxy with invalid source port is rejected`() = runBlocking {
        val testPort = ServerSocket(0).use { it.localPort }

        val server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }

            listener.enableStartTls = false
            listener.enableAuth = false
            listener.implicitTls = false

            proxyProtocol.enabled = true
            proxyProtocol.trustedProxyCidrs = listOf("127.0.0.1/32")
        }

        server.start()
        Thread.sleep(100)

        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 1_500
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()

                out.write("PROXY TCP4 198.51.100.10 203.0.113.5 0 25\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val closed = try {
                    val line = reader.readLine()
                    line == null
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: Exception) {
                    true
                }

                assertTrue(closed, "Expected connection close for invalid source port in PROXY header")
            }
        } finally {
            server.stop(gracefulTimeoutMs = 5_000)
        }
    }
}

