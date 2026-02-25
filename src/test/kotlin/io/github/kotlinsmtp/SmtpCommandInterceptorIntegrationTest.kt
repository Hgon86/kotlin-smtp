package io.github.kotlinsmtp

import io.github.kotlinsmtp.server.SmtpServer
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptor
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorAction
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandInterceptorContext
import io.github.kotlinsmtp.spi.pipeline.SmtpCommandStage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertTrue

// shared SMTP test helpers (skipEhloResponse, etc.)

class SmtpCommandInterceptorIntegrationTest {

    private lateinit var server: SmtpServer
    private var testPort: Int = 0

    @BeforeEach
    fun setup() {
        testPort = ServerSocket(0).use { it.localPort }
        startServerWith(
            object : SmtpCommandInterceptor {
                override suspend fun intercept(
                    stage: SmtpCommandStage,
                    context: SmtpCommandInterceptorContext,
                ): SmtpCommandInterceptorAction {
                    if (stage == SmtpCommandStage.MAIL_FROM) {
                        return SmtpCommandInterceptorAction.Deny(550, "5.7.1 MAIL blocked by interceptor")
                    }
                    return SmtpCommandInterceptorAction.Proceed
                }
            },
        )
    }

    private fun startServerWith(interceptor: SmtpCommandInterceptor) {
        server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useTransactionProcessorFactory { TestSmtpTransactionProcessor() }

            addCommandInterceptor(interceptor)

            listener.enableStartTls = false
            listener.enableAuth = false
            listener.implicitTls = false
            proxyProtocol.enabled = false
        }

        runBlocking {
            server.start()
            Thread.sleep(100)
        }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            server.stop(gracefulTimeoutMs = 5000)
        }
    }

    /**
     * MAIL command can be denied by command-stage interceptor before core handler execution.
     */
    @Test
    fun `MAIL FROM is denied by command interceptor`() {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            reader.skipEhloResponse()

            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()

            val response = reader.readLine()
            assertTrue(response.startsWith("550"), "Expected MAIL denial from interceptor, got: $response")
        }
    }

    @Test
    fun `MAIL FROM can drop connection via interceptor`() {
        runBlocking {
            server.stop(gracefulTimeoutMs = 5000)
        }

        startServerWith(
            object : SmtpCommandInterceptor {
                override suspend fun intercept(
                    stage: SmtpCommandStage,
                    context: SmtpCommandInterceptorContext,
                ): SmtpCommandInterceptorAction {
                    if (stage == SmtpCommandStage.MAIL_FROM) {
                        return SmtpCommandInterceptorAction.Drop(421, "4.7.1 Connection blocked by interceptor")
                    }
                    return SmtpCommandInterceptorAction.Proceed
                }
            },
        )

        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            reader.skipEhloResponse()

            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()

            val response = reader.readLine()
            assertTrue(response.startsWith("421"), "Expected 421 drop response from interceptor, got: $response")
            val nextLine = runCatching { reader.readLine() }
            assertTrue(
                nextLine.isFailure || nextLine.getOrNull() == null,
                "Expected connection close after drop response",
            )
        }
    }
}

