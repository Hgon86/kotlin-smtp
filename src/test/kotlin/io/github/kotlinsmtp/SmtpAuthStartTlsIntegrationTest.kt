package io.github.kotlinsmtp

import io.github.kotlinsmtp.auth.AuthService
import io.github.kotlinsmtp.model.SmtpUser
import io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler
import io.github.kotlinsmtp.protocol.handler.SmtpUserHandler
import io.github.kotlinsmtp.server.SmtpDomainSpooler
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
    private var lastTriggeredDomain: String? = null

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
        lastTriggeredDomain = null

        server = SmtpServer.create(testPort, "test-smtp.local") {
            serviceName = "test-smtp"
            useAuthService(this@SmtpAuthStartTlsIntegrationTest.authService)
            useProtocolHandlerFactory { TestSmtpProtocolHandler() }
            useUserHandler(object : SmtpUserHandler() {
                override fun verify(searchTerm: String): Collection<SmtpUser> {
                    if (!searchTerm.equals("user", ignoreCase = true)) return emptyList()
                    return listOf(SmtpUser(localPart = "user", domain = "test-smtp.local", username = "Test User"))
                }
            })
            useMailingListHandler(object : SmtpMailingListHandler {
                override fun expand(listName: String): List<String> {
                    if (!listName.equals("dev-team", ignoreCase = true)) return emptyList()
                    return listOf("alice@test-smtp.local", "bob@test-smtp.local")
                }
            })
            useSpooler(object : SmtpDomainSpooler {
                override fun triggerOnce() {
                    // no-op
                }

                override fun triggerOnce(domain: String) {
                    lastTriggeredDomain = domain
                }
            })

            listener.enableStartTls = true
            listener.enableAuth = true
            listener.requireAuthForMail = true
            listener.implicitTls = false
            features.enableVrfy = true
            features.enableExpn = true
            features.enableEtrn = true

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
    fun `STARTTLS cannot be pipelined with other commands`() {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            reader.readLine() // greeting

            out.write("EHLO test.client.local\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            skipEhloResponse(reader)

            // The server must reject pipelined commands before STARTTLS 220 response is received.
            out.write("STARTTLS\r\nMAIL FROM:<sender@test.com>\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val resp = reader.readLine()
            assertTrue(resp.startsWith("501"), "Expected 501 for STARTTLS pipelining, got: $resp")

            // Conservatively verify connection close behavior (EOF/timeout, etc.).
            runCatching { reader.readLine() }
        }
    }

    @Test
    fun `STARTTLS with parameter returns syntax error`() {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("STARTTLS now\r\n")
            writer.flush()

            val resp = reader.readLine()
            assertTrue(resp.startsWith("501"), "Expected 501 for STARTTLS with parameter, got: $resp")
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
            val ehloLines = readEhloLines(reader)
            assertTrue(ehloLines.isNotEmpty(), "Expected EHLO response after STARTTLS")
            assertTrue(ehloLines.last().startsWith("250 "), "Expected final 250 line, got: ${ehloLines.lastOrNull()}")

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

    /**
     * MAIL FROM must be rejected before authentication, even after STARTTLS + EHLO.
     */
    @Test
    fun `MAIL FROM is rejected after STARTTLS when auth is required but not authenticated`() {
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
            val ehloLines = readEhloLines(reader)
            assertTrue(ehloLines.isNotEmpty(), "Expected EHLO response after STARTTLS")
            assertTrue(ehloLines.last().startsWith("250 "), "Expected final 250 line, got: ${ehloLines.lastOrNull()}")

            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            val mailResp = reader.readLine()
            assertTrue(mailResp.startsWith("530"), "Expected 530 when auth is required, got: $mailResp")
            assertTrue(mailResp.contains("Authentication required", ignoreCase = true), "Expected auth required hint, got: $mailResp")

            tlsSocket.close()
        }
    }

    /**
     * Invalid AUTH PLAIN credentials must be rejected with 535.
     */
    @Test
    fun `AUTH PLAIN failure returns 535`() {
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

            writer.write(buildAuthPlainLine("user", "wrong-password"))
            writer.flush()
            val authResp = reader.readLine()
            assertTrue(authResp.startsWith("535"), "Expected 535 Authentication credentials invalid, got: $authResp")

            tlsSocket.close()
        }
    }

    @Test
    fun `AUTH LOGIN success returns 250`() {
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

            val userB64 = Base64.getEncoder().encodeToString("user".toByteArray(Charsets.UTF_8))
            val passB64 = Base64.getEncoder().encodeToString("password".toByteArray(Charsets.UTF_8))

            writer.write("AUTH LOGIN\r\n")
            writer.flush()
            val userPrompt = reader.readLine()
            assertTrue(userPrompt.startsWith("334"), "Expected username challenge, got: $userPrompt")

            writer.write("$userB64\r\n")
            writer.flush()
            val passPrompt = reader.readLine()
            assertTrue(passPrompt.startsWith("334"), "Expected password challenge, got: $passPrompt")

            writer.write("$passB64\r\n")
            writer.flush()
            val authResp = reader.readLine()
            assertTrue(authResp.startsWith("250"), "Expected 250 Authentication successful, got: $authResp")

            tlsSocket.close()
        }
    }

    @Test
    fun `AUTH LOGIN invalid username returns 501`() {
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

            writer.write("AUTH LOGIN\r\n")
            writer.flush()
            val userPrompt = reader.readLine()
            assertTrue(userPrompt.startsWith("334"), "Expected username challenge, got: $userPrompt")

            writer.write("***NOT_BASE64***\r\n")
            writer.flush()
            val authResp = reader.readLine()
            assertTrue(authResp.startsWith("501"), "Expected 501 invalid username response, got: $authResp")

            tlsSocket.close()
        }
    }

    /**
     * VRFY should return matched user details when enabled and handler is configured.
     */
    @Test
    fun `VRFY returns matched user`() {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // greeting

            writer.write("EHLO test.client.local\r\n")
            writer.flush()
            skipEhloResponse(reader)

            writer.write("VRFY user\r\n")
            writer.flush()
            val resp = reader.readLine()
            assertTrue(resp.startsWith("250"), "Expected 250 for VRFY user, got: $resp")
            assertTrue(resp.contains("user@test-smtp.local"), "Expected matched user in VRFY response, got: $resp")
        }
    }

    /**
     * EXPN should require authentication even when the feature is enabled.
     */
    @Test
    fun `EXPN requires authentication`() {
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

            writer.write("EXPN dev-team\r\n")
            writer.flush()
            val expnResp = reader.readLine()
            assertTrue(expnResp.startsWith("530"), "Expected 530 when EXPN is used without auth, got: $expnResp")

            tlsSocket.close()
        }
    }

    /**
     * EXPN should return list members after successful STARTTLS + AUTH.
     */
    @Test
    fun `EXPN returns members after auth`() {
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

            writer.write("EXPN dev-team\r\n")
            writer.flush()

            val expnLines = mutableListOf<String>()
            var line = reader.readLine()
            while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
                expnLines.add(line)
                if (line.startsWith("250 ")) break
                line = reader.readLine()
            }

            assertTrue(expnLines.isNotEmpty(), "Expected EXPN multiline response")
            assertTrue(expnLines.any { it.contains("alice@test-smtp.local") }, "Expected alice member in EXPN response")
            assertTrue(expnLines.any { it.contains("bob@test-smtp.local") }, "Expected bob member in EXPN response")

            tlsSocket.close()
        }
    }

    @Test
    fun `ETRN with empty argument returns syntax error`() {
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

            writer.write("ETRN\r\n")
            writer.flush()
            val etrnResp = reader.readLine()
            assertTrue(etrnResp.startsWith("501"), "Expected 501 for empty ETRN argument, got: $etrnResp")

            tlsSocket.close()
        }
    }

    @Test
    fun `ETRN with domain succeeds after auth`() {
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

            writer.write("ETRN example.com\r\n")
            writer.flush()
            val etrnResp = reader.readLine()
            assertTrue(etrnResp.startsWith("250"), "Expected 250 for valid ETRN, got: $etrnResp")
            assertTrue(etrnResp.contains("example.com"), "Expected domain in ETRN response, got: $etrnResp")
            assertTrue(lastTriggeredDomain == "example.com", "Expected domain-aware spool trigger, got: $lastTriggeredDomain")

            tlsSocket.close()
        }
    }

    @Test
    fun `ETRN with invalid domain returns syntax error`() {
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

            writer.write("ETRN !!invalid!!\r\n")
            writer.flush()
            val etrnResp = reader.readLine()
            assertTrue(etrnResp.startsWith("501"), "Expected 501 for invalid ETRN domain, got: $etrnResp")

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
        // Wait briefly after TLS handshake for stable I/O.
        Thread.sleep(50)
        return tls
    }
}
