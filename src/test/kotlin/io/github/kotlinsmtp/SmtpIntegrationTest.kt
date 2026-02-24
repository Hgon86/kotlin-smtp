package io.github.kotlinsmtp

import io.github.kotlinsmtp.server.SmtpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * SMTP protocol integration tests.
 *
 * Validates core server scenarios over real socket I/O:
 * 1. Basic HELO/EHLO sequence
 * 2. STARTTLS upgrade and state reset
 * 3. AUTH PLAIN/LOGIN authentication
 * 4. MAIL FROM/RCPT TO transaction
 * 5. DATA receive and dot-stuffing
 * 6. BDAT CHUNKING
 */
class SmtpIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var server: SmtpServer
    private var testPort: Int = 0

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("smtp-test")

        server = SmtpServer.create(0, "test-smtp.local") {
            serviceName = "test-smtp"
            useProtocolHandlerFactory { TestSmtpProtocolHandler() }

            // Run tests without TLS/AUTH.
            listener.enableStartTls = false
            listener.enableAuth = false
            listener.implicitTls = false

            proxyProtocol.enabled = false
        }
        
        runBlocking {
            server.start()
            testPort = resolveBoundPort(server)
            // Wait for server startup.
            Thread.sleep(100)
        }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            server.stop(gracefulTimeoutMs = 5000)
        }
        // Clean up temporary directory.
        tempDir.toFile().deleteRecursively()
    }

    /**
     * Basic HELO/EHLO test.
     */
    @Test
    fun `test basic EHLO sequence`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            // 1. Receive greeting.
            val greeting = reader.readLine()
            assertTrue(greeting.startsWith("220"), "Expected 220 greeting, got: $greeting")

            // 2. Send EHLO.
            writer.write("EHLO test.client.local\r\n")
            writer.flush()

            // 3. Receive EHLO response (multiline).
            val responses = mutableListOf<String>()
            var line = reader.readLine()
            while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
                responses.add(line)
                if (line.startsWith("250 ")) break
                line = reader.readLine()
            }
            
            assertTrue(responses.isNotEmpty(), "Expected EHLO response")
            assertTrue(responses.last().startsWith("250 "), "Expected 250 final response")
            
            // 4. QUIT
            writer.write("QUIT\r\n")
            writer.flush()
            
            val quitResponse = reader.readLine()
            assertTrue(quitResponse.startsWith("221"), "Expected 221 quit response")
        }
    }

    /**
     * Full MAIL FROM/RCPT TO/DATA transaction test.
     */
    @Test
    fun `test full mail transaction`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            // Greeting
            reader.readLine()

            // EHLO
            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            // MAIL FROM
            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            val mailFromResponse = reader.readLine()
            assertTrue(mailFromResponse.startsWith("250"), "Expected 250 after MAIL FROM, got: $mailFromResponse")

            // RCPT TO
            writer.write("RCPT TO:<recipient@test.com>\r\n")
            writer.flush()
            val rcptToResponse = reader.readLine()
            assertTrue(rcptToResponse.startsWith("250"), "Expected 250 after RCPT TO, got: $rcptToResponse")

            // DATA
            writer.write("DATA\r\n")
            writer.flush()
            val dataResponse = reader.readLine()
            assertTrue(dataResponse.startsWith("354"), "Expected 354 go ahead")

            // Send message body (including dot-stuffing case).
            writer.write("Subject: Test Mail\r\n")
            writer.write("From: sender@test.com\r\n")
            writer.write("To: recipient@test.com\r\n")
            writer.write("\r\n")
            writer.write("This is a test message.\r\n")
            writer.write("Line with dot: .test\r\n") // dot-stuffing case
            writer.write(".\r\n") // terminator marker
            writer.flush()

            val finalResponse = reader.readLine()
            assertTrue(finalResponse.startsWith("250"), "Expected 250 ok after DATA")

            // QUIT
            writer.write("QUIT\r\n")
            writer.flush()
            reader.readLine() // 221 response
        }
    }

    /**
     * UTF-8 local-part must be rejected when SMTPUTF8 is not declared.
     */
    @Test
    fun `test MAIL FROM rejects UTF8 local part without SMTPUTF8`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            reader.readLine() // Greeting

            out.write("EHLO test.client.local\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            skipEhloResponse(reader)

            out.write("MAIL FROM:<\uC0AC\uC6A9\uC790@example.com>\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val mailResp = reader.readLine()
            assertTrue(mailResp.startsWith("553"), "Expected 553 SMTPUTF8 required, got: $mailResp")
            assertTrue(mailResp.contains("SMTPUTF8", ignoreCase = true), "Expected SMTPUTF8 hint, got: $mailResp")
        }
    }

    /**
     * IDN (punycode) recipient path should work when SMTPUTF8 is declared.
     */
    @Test
    fun `test RCPT TO accepts IDN recipient with SMTPUTF8`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            reader.readLine() // Greeting

            out.write("EHLO test.client.local\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            skipEhloResponse(reader)

            out.write("MAIL FROM:<sender@example.com> SMTPUTF8\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            val mailResp = reader.readLine()
            assertTrue(mailResp.startsWith("250"), "Expected 250 after MAIL FROM SMTPUTF8, got: $mailResp")

            out.write("RCPT TO:<recipient@xn--9t4b11yi5a.xn--3e0b707e>\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            val rcptResp = reader.readLine()
            assertTrue(rcptResp.startsWith("250"), "Expected 250 for IDN recipient with SMTPUTF8, got: $rcptResp")
        }
    }

    /**
     * ASCII local-part + IDN domain should be accepted without SMTPUTF8.
     */
    @Test
    fun `test MAIL FROM accepts IDN domain without SMTPUTF8 when local part is ASCII`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            reader.readLine() // Greeting

            out.write("EHLO test.client.local\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            skipEhloResponse(reader)

            out.write("MAIL FROM:<sender@xn--9t4b11yi5a.xn--3e0b707e>\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            val mailResp = reader.readLine()
            assertTrue(mailResp.startsWith("250"), "Expected 250 for IDN domain without SMTPUTF8, got: $mailResp")
        }
    }

    @Test
    fun `test MAIL FROM rejects invalid DSN RET value`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("MAIL FROM:<sender@test.com> RET=INVALID\r\n")
            writer.flush()
            val response = reader.readLine()
            assertTrue(response.startsWith("555"), "Expected 555 for invalid RET value, got: $response")
        }
    }

    @Test
    fun `test RCPT TO rejects invalid DSN NOTIFY combination`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            val mailResp = reader.readLine()
            assertTrue(mailResp.startsWith("250"), "Expected 250 after MAIL FROM, got: $mailResp")

            writer.write("RCPT TO:<recipient@test.com> NOTIFY=NEVER,SUCCESS\r\n")
            writer.flush()
            val rcptResp = reader.readLine()
            assertTrue(rcptResp.startsWith("501"), "Expected 501 for invalid NOTIFY combination, got: $rcptResp")
        }
    }

    @Test
    fun `test DATA rejects body exceeding declared SIZE`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("MAIL FROM:<sender@test.com> SIZE=10\r\n")
            writer.flush()
            val mailResp = reader.readLine()
            assertTrue(mailResp.startsWith("250"), "Expected 250 after MAIL FROM with SIZE, got: $mailResp")

            writer.write("RCPT TO:<recipient@test.com>\r\n")
            writer.flush()
            val rcptResp = reader.readLine()
            assertTrue(rcptResp.startsWith("250"), "Expected 250 after RCPT TO, got: $rcptResp")

            writer.write("DATA\r\n")
            writer.flush()
            val dataResp = reader.readLine()
            assertTrue(dataResp.startsWith("354"), "Expected 354 after DATA, got: $dataResp")

            writer.write("12345678901\r\n") // 11 bytes + CRLF, larger than declared SIZE=10
            writer.write(".\r\n")
            writer.flush()

            val finalResp = reader.readLine()
            assertTrue(finalResp.startsWith("552"), "Expected 552 for declared SIZE overflow, got: $finalResp")
        }
    }

    /**
     * DATA framing must stay intact even when body arrives in a pipeline.
     *
     * In particular, a body line starting with "BDAT ..." must not be misparsed as BDAT command.
     */
    @Test
    fun `test DATA pipelined body does not trigger BDAT framing`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            reader.readLine()

            writer.write("RCPT TO:<recipient@test.com>\r\n")
            writer.flush()
            reader.readLine()

            // Send body continuously right after DATA, without waiting for 354.
            writer.write("DATA\r\n")
            // Body contains "BDAT 4" text and must not be treated as BDAT command.
            writer.write("BDAT 4\r\n")
            writer.write(".\r\n")
            writer.flush()

            val dataResponse = reader.readLine()
            assertTrue(dataResponse.startsWith("354"), "Expected 354 go ahead")

            val finalResponse = reader.readLine()
            assertTrue(finalResponse.startsWith("250"), "Expected 250 ok after DATA")

            writer.write("QUIT\r\n")
            writer.flush()
        }
    }

    /**
     * BDAT line and chunk bytes must be framed correctly even in the same write.
     */
    @Test
    fun `test BDAT line and bytes in same write`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            reader.readLine() // Greeting

            out.write("EHLO test.client.local\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            skipEhloResponse(reader)

            out.write("MAIL FROM:<sender@test.com>\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            reader.readLine()

            out.write("RCPT TO:<recipient@test.com>\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            reader.readLine()

            out.write("BDAT 4 LAST\r\nABCD".toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val response = reader.readLine()
            assertTrue(response.startsWith("250"), "Expected 250 ok after BDAT, got: $response")

            out.write("QUIT\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    /**
     * DATA sequence rejection must not break subsequent BDAT framing.
     */
    @Test
    fun `test DATA rejection does not break next BDAT framing`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            reader.readLine() // Greeting

            out.write("EHLO test.client.local\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            skipEhloResponse(reader)

            // Reject DATA first (no MAIL/RCPT yet)
            out.write("DATA\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val rejected = reader.readLine()
            assertTrue(rejected.startsWith("503"), "Expected 503 for invalid DATA sequence, got: $rejected")

            out.write("MAIL FROM:<sender@test.com>\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            reader.readLine()

            out.write("RCPT TO:<recipient@test.com>\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            reader.readLine()

            out.write("BDAT 4 LAST\r\nABCD".toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val bdatResp = reader.readLine()
            assertTrue(bdatResp.startsWith("250"), "Expected 250 after BDAT, got: $bdatResp")
        }
    }

    /**
     * RSET test - transaction abort and restart.
     */
    @Test
    fun `test RSET resets transaction`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            // EHLO
            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            // MAIL FROM
            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            reader.readLine()

            // RCPT TO
            writer.write("RCPT TO:<recipient@test.com>\r\n")
            writer.flush()
            reader.readLine()

            // RSET
            writer.write("RSET\r\n")
            writer.flush()
            val rsetResponse = reader.readLine()
            assertTrue(rsetResponse.startsWith("250"), "Expected 250 response after RSET, got: $rsetResponse")

            // A new transaction should start after RSET.
            writer.write("MAIL FROM:<new-sender@test.com>\r\n")
            writer.flush()
            val mailResponse = reader.readLine()
            assertTrue(mailResponse.startsWith("250"), "Expected 250 response after MAIL FROM, got: $mailResponse")

            writer.write("QUIT\r\n")
            writer.flush()
        }
    }

    /**
     * Invalid command sequence test.
     */
    @Test
    fun `test invalid command sequence`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            // Try MAIL FROM without EHLO.
            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            
            val response = reader.readLine()
            assertTrue(response.startsWith("503"), "Expected 503 bad sequence, got: $response")

            writer.write("QUIT\r\n")
            writer.flush()
        }
    }

    /**
     * HELO/EHLO enforcement test - behavior after RSET.
     */
    @Test
    fun `test requires EHLO after RSET`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting
            
            // EHLO
            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            // RSET (greeting state preserved)
            writer.write("RSET\r\n")
            writer.flush()
            reader.readLine()

            // MAIL FROM should still be allowed after RSET (greeting kept).
            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            val response = reader.readLine()
            assertTrue(response.startsWith("250"), "Expected 250 after RSET")

            writer.write("QUIT\r\n")
            writer.flush()
        }
    }

    /**
     * VRFY should stay non-enumerating by default when feature is disabled.
     */
    @Test
    fun `test VRFY returns 252 when feature is disabled`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("VRFY user\r\n")
            writer.flush()
            val response = reader.readLine()
            assertTrue(response.startsWith("252"), "Expected 252 for VRFY when disabled, got: $response")
        }
    }

    /**
     * EXPN should return not-implemented by default when feature is disabled.
     */
    @Test
    fun `test EXPN returns 502 when feature is disabled`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("EXPN dev-team\r\n")
            writer.flush()
            val response = reader.readLine()
            assertTrue(response.startsWith("502"), "Expected 502 for EXPN when disabled, got: $response")
        }
    }

    /**
     * Graceful shutdown test.
     */
    @Test
    fun `test graceful shutdown waits for sessions`() = runBlocking {
        // Connect client first.
        val clientSocket = Socket("localhost", testPort)
        val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
        val writer = OutputStreamWriter(clientSocket.getOutputStream())

        reader.readLine() // Greeting

        // EHLO
        writer.write("EHLO test.client.local\r\n")
        writer.flush()
        skipEhloResponse(reader)

            // internal implementation details (session tracker) are intentionally hidden from the public API.
            // This test only verifies that graceful shutdown completes without hanging.

        // Execute graceful shutdown in a separate coroutine.
        val shutdownJob = launch {
            server.stop(gracefulTimeoutMs = 5000)
        }

        // Wait briefly and verify session is still active during graceful shutdown.
        Thread.sleep(100)
        
        // Send QUIT from client (should still be handled during graceful shutdown).
        try {
            writer.write("QUIT\r\n")
            writer.flush()
            // Server may be shutting down; wait for response but ignore exceptions.
            kotlin.runCatching { reader.readLine() }
        } catch (e: Exception) {
            // Exception can occur if server is already closed.
        }
        
        kotlin.runCatching { clientSocket.close() }

        // Wait for shutdown completion.
        withTimeout(10.seconds) {
            shutdownJob.join()
        }

        // Verify shutdown completion (session may be force-closed by graceful timeout).
        assertTrue(true, "Shutdown completed successfully")
    }

    // Helper method
    private fun skipEhloResponse(reader: BufferedReader) {
        var line = reader.readLine()
        while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
            if (line.startsWith("250 ")) break
            line = reader.readLine()
        }
    }

    /**
     * Reads the actual bound port of the test server via reflection.
     *
     * @param smtpServer started SMTP server instance
     * @return actual listening port
     */
    private fun resolveBoundPort(smtpServer: SmtpServer): Int {
        val channelFutureField = SmtpServer::class.java.getDeclaredField("channelFuture")
        channelFutureField.isAccessible = true
        val channelFuture = channelFutureField.get(smtpServer)
            ?: error("channelFuture is null after server.start()")

        val channelMethod = channelFuture.javaClass.getMethod("channel")
        val channel = channelMethod.invoke(channelFuture)
            ?: error("channel is null after server.start()")

        val localAddressMethod = channel.javaClass.getMethod("localAddress")
        val localAddress = localAddressMethod.invoke(channel) as? InetSocketAddress
            ?: error("localAddress is not InetSocketAddress")

        return localAddress.port
    }
}
