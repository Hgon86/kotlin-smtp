package io.github.kotlinsmtp

import io.github.kotlinsmtp.server.SmtpServer
import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.assertTrue
import kotlin.test.fail

// shared SMTP test helpers (skipEhloResponse, etc.)

class SmtpStartTlsHandshakeTimeoutIntegrationTest {

    @Test
    fun `STARTTLS handshake timeout closes connection when client does not start TLS`() {
        val testPort = ServerSocket(0).use { it.localPort }
        val ssc = SelfSignedCertificate()

        val server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }

            listener.enableStartTls = true
            listener.enableAuth = false
            listener.implicitTls = false

            proxyProtocol.enabled = false

            tls.certChainPath = ssc.certificate().toPath()
            tls.privateKeyPath = ssc.privateKey().toPath()
            tls.handshakeTimeoutMs = 500
        }

        runBlocking {
            server.start()
            Thread.sleep(100)
        }

        try {
            Socket("localhost", testPort).use { socket ->
                socket.soTimeout = 2_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = OutputStreamWriter(socket.getOutputStream())

                val greeting = reader.readLine()
                assertTrue(greeting.startsWith("220"), "Expected 220 greeting, got: $greeting")

                writer.write("EHLO test.client.local\r\n")
                writer.flush()
                reader.skipEhloResponse()

                writer.write("STARTTLS\r\n")
                writer.flush()
                val startTlsResp = reader.readLine()
                assertTrue(startTlsResp.startsWith("220"), "Expected 220 Ready to start TLS, got: $startTlsResp")

                // If client does not start TLS handshake, server must close on handshake timeout.
                Thread.sleep(1_000)

                try {
                    val next = reader.readLine()
                    if (next != null) {
                        fail("Expected connection to be closed after handshake timeout, got line: $next")
                    }
                } catch (e: SocketTimeoutException) {
                    fail("Expected connection close after handshake timeout, but read timed out")
                }
            }
        } finally {
            runBlocking {
                server.stop(gracefulTimeoutMs = 5_000)
            }
            runCatching { ssc.delete() }
        }
    }
}

