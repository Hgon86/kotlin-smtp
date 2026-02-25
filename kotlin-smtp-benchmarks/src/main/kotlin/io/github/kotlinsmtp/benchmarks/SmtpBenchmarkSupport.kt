package io.github.kotlinsmtp.benchmarks

import io.github.kotlinsmtp.protocol.handler.SmtpTransactionProcessor
import io.github.kotlinsmtp.server.SmtpServer
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * SMTP benchmark runtime helper utilities.
 */
public object SmtpBenchmarkSupport {

    /**
     * Finds an available local TCP port.
     *
     * @return available port number
     */
    @JvmStatic
    public fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Starts an SMTP server for benchmark traffic.
     *
     * @param port target bind port
     * @return started SMTP server
     */
    @JvmStatic
    public fun startServer(port: Int): SmtpServer {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
        }

        val server = SmtpServer.create(port, "benchmark-smtp.local") {
            serviceName = "benchmark-smtp"
            useTransactionProcessorFactory { BenchmarkSmtpTransactionProcessor() }
            listener.enableStartTls = false
            listener.enableAuth = false
            listener.implicitTls = false
            proxyProtocol.enabled = false
            rateLimit.maxConnectionsPerIp = 100_000
            rateLimit.maxMessagesPerIpPerHour = 10_000_000
        }

        runBlocking {
            server.start()
        }

        return server
    }

    /**
     * Stops a benchmark SMTP server.
     *
     * @param server started SMTP server
     */
    @JvmStatic
    public fun stopServer(server: SmtpServer): Unit = runBlocking {
        server.stop(gracefulTimeoutMs = 5000)
    }

    /**
     * Executes one full SMTP transaction and returns elapsed nanoseconds.
     *
     * @param host SMTP host
     * @param port SMTP port
     * @param bodyBytes message body size in bytes
     * @return end-to-end transaction latency in nanoseconds
     */
    @JvmStatic
    public fun sendMessage(host: String, port: Int, bodyBytes: Int): Long {
        val body = "a".repeat(bodyBytes.coerceAtLeast(1))
        val startedAt = System.nanoTime()

        Socket(host, port).use { socket ->
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)

            readExpected(reader, "220")

            writeLine(writer, "EHLO benchmark.client.local")
            skipEhlo(reader)

            writeLine(writer, "MAIL FROM:<bench.sender@test.local>")
            readExpected(reader, "250")

            writeLine(writer, "RCPT TO:<bench.recipient@test.local>")
            readExpected(reader, "250")

            writeLine(writer, "DATA")
            readExpected(reader, "354")

            writer.write("Subject: benchmark\r\n")
            writer.write("From: bench.sender@test.local\r\n")
            writer.write("To: bench.recipient@test.local\r\n")
            writer.write("\r\n")
            writeWrappedBody(writer, body)
            writer.write(".\r\n")
            writer.flush()

            readExpected(reader, "250")

            writeLine(writer, "QUIT")
            readExpected(reader, "221")
        }

        return System.nanoTime() - startedAt
    }

    private fun writeLine(writer: OutputStreamWriter, line: String) {
        writer.write(line)
        writer.write("\r\n")
        writer.flush()
    }

    private fun skipEhlo(reader: BufferedReader) {
        var line = reader.readLine()
        while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
            if (line.startsWith("250 ")) {
                return
            }
            line = reader.readLine()
        }
        error("Invalid EHLO response: $line")
    }

    private fun readExpected(reader: BufferedReader, codePrefix: String) {
        val line = reader.readLine() ?: error("Connection closed while waiting for $codePrefix")
        check(line.startsWith(codePrefix)) {
            "Expected response $codePrefix, got: $line"
        }
    }

    private fun writeWrappedBody(writer: OutputStreamWriter, body: String) {
        val chunkSize = 512
        var index = 0
        while (index < body.length) {
            val end = (index + chunkSize).coerceAtMost(body.length)
            writer.write(body, index, end - index)
            writer.write("\r\n")
            index = end
        }
    }
}

/**
 * Transaction processor used by benchmark SMTP server.
 */
public class BenchmarkSmtpTransactionProcessor : SmtpTransactionProcessor() {

    /**
     * Consumes incoming DATA stream without persistence overhead.
     *
     * @param inputStream incoming message stream
     * @param size declared message size
     */
    override suspend fun data(inputStream: InputStream, size: Long) {
        inputStream.use { stream ->
            val buffer = ByteArray(8192)
            while (stream.read(buffer) != -1) {
            }
        }
    }
}
