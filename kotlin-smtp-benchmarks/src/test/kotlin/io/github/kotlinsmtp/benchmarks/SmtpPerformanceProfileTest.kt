package io.github.kotlinsmtp.benchmarks

import io.github.kotlinsmtp.server.SmtpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end SMTP performance profile test for documentation baselines.
 */
@Tag("performance")
@EnabledIfSystemProperty(named = "kotlinsmtp.performance.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmtpPerformanceProfileTest {

    private lateinit var server: SmtpServer
    private var port: Int = 0

    @BeforeAll
    fun setUp() {
        port = SmtpBenchmarkSupport.findFreePort()
        server = SmtpBenchmarkSupport.startServer(port)
    }

    @AfterAll
    fun tearDown() {
        SmtpBenchmarkSupport.stopServer(server)
    }

    /**
     * Collects throughput and latency profile from concurrent SMTP transactions.
     */
    @Test
    fun `collect smtp throughput and latency profile`() {
        val clients = intProperty("kotlinsmtp.performance.clients", 8)
        val messagesPerClient = intProperty("kotlinsmtp.performance.messagesPerClient", 200)
        val bodyBytes = intProperty("kotlinsmtp.performance.bodyBytes", 4096)
        val totalMessages = clients * messagesPerClient

        val pool = Executors.newFixedThreadPool(clients)
        val startedAt = System.nanoTime()

        try {
            val tasks = (0 until clients).map {
                Callable {
                    buildList(messagesPerClient) {
                        repeat(messagesPerClient) {
                            add(SmtpBenchmarkSupport.sendMessage("127.0.0.1", port, bodyBytes))
                        }
                    }
                }
            }

            val latencies = pool.invokeAll(tasks)
                .flatMap { it.get() }

            val elapsedNanos = System.nanoTime() - startedAt
            val throughput = totalMessages / (elapsedNanos / 1_000_000_000.0)

            val sorted = latencies.sorted()
            val avgMs = sorted.average() / 1_000_000.0
            val p50Ms = percentile(sorted, 0.50) / 1_000_000.0
            val p95Ms = percentile(sorted, 0.95) / 1_000_000.0
            val maxMs = sorted.last() / 1_000_000.0

            println("[kotlin-smtp-performance]")
            println("clients=$clients, messagesPerClient=$messagesPerClient, bodyBytes=$bodyBytes")
            println("totalMessages=$totalMessages")
            println("throughput(emails/s)=${format(throughput)}")
            println("latency(avg/p50/p95/max, ms)=${format(avgMs)}/${format(p50Ms)}/${format(p95Ms)}/${format(maxMs)}")

            assertEquals(totalMessages, sorted.size, "All SMTP transactions must complete")
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    private fun intProperty(name: String, defaultValue: Int): Int {
        return System.getProperty(name)?.toIntOrNull() ?: defaultValue
    }

    private fun percentile(sorted: List<Long>, ratio: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * ratio).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index].toDouble()
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
