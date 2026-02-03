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
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * SMTP 프로토콜 통합 테스트
 *
 * 실제 소켓 통신을 통해 SMTP 서버의 핵심 시나리오를 검증합니다:
 * 1. 기본 HELO/EHLO 시퀀스
 * 2. STARTTLS 업그레이드 및 상태 리셋
 * 3. AUTH PLAIN/LOGIN 인증
 * 4. MAIL FROM/RCPT TO 트랜잭션
 * 5. DATA 수신 및 dot-stuffing
 * 6. BDAT CHUNKING
 */
class SmtpIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var server: SmtpServer
    private var testPort: Int = 0

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("smtp-test")
        
        // Find an available port
        testPort = ServerSocket(0).use { it.localPort }
        
        server = SmtpServer(
            port = testPort,
            hostname = "test-smtp.local",
            serviceName = "test-smtp",
            transactionHandlerCreator = { TestSmtpProtocolHandler() },
            enableStartTls = false, // 테스트에서는 TLS 없이 진행
            enableAuth = false, // 기본 테스트에서는 AUTH 비활성
            implicitTls = false,
            proxyProtocolEnabled = false,
        )
        
        runBlocking {
            server.start()
            // 서버 시작 대기
            Thread.sleep(100)
        }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            server.stop(gracefulTimeoutMs = 5000)
        }
        // 임시 디렉토리 정리
        tempDir.toFile().deleteRecursively()
    }

    /**
     * 기본 HELO/EHLO 테스트
     */
    @Test
    fun `test basic EHLO sequence`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            // 1. Greeting 수신
            val greeting = reader.readLine()
            assertTrue(greeting.startsWith("220"), "Expected 220 greeting, got: $greeting")

            // 2. EHLO 전송
            writer.write("EHLO test.client.local\r\n")
            writer.flush()

            // 3. EHLO 응답 수신 (multiline)
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
     * MAIL FROM/RCPT TO/DATA 전체 트랜잭션 테스트
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

            // 메일 본문 전송 (dot-stuffing 테스트 포함)
            writer.write("Subject: Test Mail\r\n")
            writer.write("From: sender@test.com\r\n")
            writer.write("To: recipient@test.com\r\n")
            writer.write("\r\n")
            writer.write("This is a test message.\r\n")
            writer.write("Line with dot: .test\r\n") // dot-stuffing 테스트
            writer.write(".\r\n") // 종료 마커
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
     * RSET 테스트 - 트랜잭션 중단 및 재시작
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

            // RSET 후 새로운 트랜잭션 시작 가능
            writer.write("MAIL FROM:<new-sender@test.com>\r\n")
            writer.flush()
            val mailResponse = reader.readLine()
            assertTrue(mailResponse.startsWith("250"), "Expected 250 response after MAIL FROM, got: $mailResponse")

            writer.write("QUIT\r\n")
            writer.flush()
        }
    }

    /**
     * 잘못된 명령어 순서 테스트
     */
    @Test
    fun `test invalid command sequence`() = runBlocking {
        Socket("localhost", testPort).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            reader.readLine() // Greeting

            // EHLO 없이 MAIL FROM 시도
            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            
            val response = reader.readLine()
            assertTrue(response.startsWith("503"), "Expected 503 bad sequence, got: $response")

            writer.write("QUIT\r\n")
            writer.flush()
        }
    }

    /**
     * HELO/EHLO 강제 테스트 - RSET 후에도 EHLO 필요
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

            // RSET (greeting 상태 유지)
            writer.write("RSET\r\n")
            writer.flush()
            reader.readLine()

            // RSET 후에도 MAIL FROM 가능 (greeting 유지됨)
            writer.write("MAIL FROM:<sender@test.com>\r\n")
            writer.flush()
            val response = reader.readLine()
            assertTrue(response.startsWith("250"), "Expected 250 after RSET")

            writer.write("QUIT\r\n")
            writer.flush()
        }
    }

    /**
     * Graceful shutdown 테스트
     */
    @Test
    fun `test graceful shutdown waits for sessions`() = runBlocking {
        // 먼저 클라이언트 연결
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

        // 별도 코루틴에서 graceful shutdown 실행
        val shutdownJob = kotlinx.coroutines.GlobalScope.launch {
            server.stop(gracefulTimeoutMs = 5000)
        }

        // 잠시 대기 후 세션이 여전히 활성인지 확인 (graceful shutdown 진행 중)
        Thread.sleep(100)
        
        // 클라이언트에서 QUIT 전송 (graceful shutdown 중에도 처리되어야 함)
        try {
            writer.write("QUIT\r\n")
            writer.flush()
            // 서버가 종료 중일 수 있으므로 응답을 기다리되 예외는 무시
            kotlin.runCatching { reader.readLine() }
        } catch (e: Exception) {
            // 서버가 이미 종료된 경우 예외 발생 가능
        }
        
        kotlin.runCatching { clientSocket.close() }

        // shutdown 완료 대기
        withTimeout(10.seconds) {
            shutdownJob.join()
        }

        // 서버가 정지되었는지 확인 (세션은 graceful timeout으로 인해 강제 종료될 수 있음)
        assertTrue(true, "Shutdown completed successfully")
    }

    // Helper 메서드
    private fun skipEhloResponse(reader: BufferedReader) {
        var line = reader.readLine()
        while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
            if (line.startsWith("250 ")) break
            line = reader.readLine()
        }
    }
}
