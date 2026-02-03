package io.github.kotlinsmtp

import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.server.SmtpServer
import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.assertTrue

class SmtpAuthStartTlsIntegrationTest {

    private lateinit var server: SmtpServer
    private var testPort: Int = 0
    private lateinit var ssc: SelfSignedCertificate

    private val authService: AuthService = object : AuthService {
        override val enabled: Boolean = true
        override val required: Boolean = true

        override fun verify(username: String, password: String): Boolean =
            username == "user" && password == "password"
    }

    @BeforeEach
    fun setup() {
        testPort = ServerSocket(0).use { it.localPort }
        ssc = SelfSignedCertificate()

        server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useAuthService(this@SmtpAuthStartTlsIntegrationTest.authService)
            useProtocolHandlerFactory { TestSmtpProtocolHandler() }

            listener.enableStartTls = true
            listener.enableAuth = true
            listener.requireAuthForMail = true
            listener.implicitTls = false

            proxyProtocol.enabled = false

            tls.certChainPath = ssc.certificate().toPath()
            tls.privateKeyPath = ssc.privateKey().toPath()
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
        runCatching { ssc.delete() }
    }

    @Test
    fun `AUTH is rejected until STARTTLS is active`() {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            val greeting = reader.readLine()
            assertTrue(greeting.startsWith("220"), "Expected 220 greeting, got: $greeting")

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            val authLine = buildAuthPlainLine("user", "password")
            writer.write(authLine)
            writer.flush()

            val resp = reader.readLine()
            assertTrue(resp.startsWith("503"), "Expected 503 before STARTTLS, got: $resp")
            assertTrue(resp.contains("STARTTLS", ignoreCase = true), "Expected STARTTLS hint, got: $resp")
        }
    }

    @Test
    fun `STARTTLS requires EHLO again after handshake`() {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 5_000
            var reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("STARTTLS\r\n")
            writer.flush()
            val startTlsResp = reader.readLine()
            assertTrue(startTlsResp.startsWith("220"), "Expected 220 Ready to start TLS, got: $startTlsResp")

            val tlsSocket = wrapToTls(socket)
            reader = BufferedReader(InputStreamReader(tlsSocket.getInputStream()))
            writer = OutputStreamWriter(tlsSocket.getOutputStream())

            // After STARTTLS the server requires HELO/EHLO again
            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            val mailBeforeEhlo = reader.readLine()
            assertTrue(mailBeforeEhlo.startsWith("503"), "Expected 503 after STARTTLS before EHLO, got: $mailBeforeEhlo")

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            val ehloLines = readEhloLines(reader)
            assertTrue(ehloLines.isNotEmpty(), "Expected EHLO response after STARTTLS")
            assertTrue(ehloLines.last().startsWith("250 "), "Expected final 250 line, got: ${ehloLines.last()}")

            tlsSocket.close()
        }
    }

    @Test
    fun `STARTTLS + AUTH PLAIN success allows MAIL FROM when required`() {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 5_000
            var reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("STARTTLS\r\n")
            writer.flush()
            val startTlsResp = reader.readLine()
            assertTrue(startTlsResp.startsWith("220"), "Expected 220 Ready to start TLS, got: $startTlsResp")

            val tlsSocket = wrapToTls(socket)
            reader = BufferedReader(InputStreamReader(tlsSocket.getInputStream()))
            writer = OutputStreamWriter(tlsSocket.getOutputStream())

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write(buildAuthPlainLine("user", "password"))
            writer.flush()
            val authResp = reader.readLine()
            assertTrue(authResp.startsWith("250"), "Expected 250 Authentication successful, got: $authResp")

            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            val mailResp = reader.readLine()
            assertTrue(mailResp.startsWith("250"), "Expected 250 after MAIL FROM, got: $mailResp")

            tlsSocket.close()
        }
    }

    private fun skipEhloResponse(reader: BufferedReader) {
        var line = reader.readLine()
        while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
            if (line.startsWith("250 ")) break
            line = reader.readLine()
        }
    }

    private fun readEhloLines(reader: BufferedReader): List<String> {
        val lines = mutableListOf<String>()
        var line = reader.readLine()
        while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
            lines.add(line)
            if (line.startsWith("250 ")) break
            line = reader.readLine()
        }
        return lines
    }

    private fun buildAuthPlainLine(username: String, password: String): String {
        val raw = "\u0000${username}\u0000${password}".toByteArray(Charsets.UTF_8)
        val b64 = Base64.getEncoder().encodeToString(raw)
        return "AUTH PLAIN $b64\r\n"
    }

    private fun wrapToTls(socket: Socket): SSLSocket {
        val trustAll: TrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustAll), java.security.SecureRandom())

        val factory = ctx.socketFactory
        val tls = factory.createSocket(socket, "localhost", socket.port, true) as SSLSocket
        tls.useClientMode = true
        tls.startHandshake()
        return tls
    }
}
