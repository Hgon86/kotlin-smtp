package io.github.kotlinsmtp.benchmarks;

import io.github.kotlinsmtp.server.SmtpServer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * End-to-end SMTP transaction benchmark over loopback socket.
 */
@State(Scope.Benchmark)
public class SmtpTransactionBenchmark {

    /**
     * Message body size for one SMTP transaction.
     */
    @Param({"256", "4096", "65536"})
    public int bodyBytes;

    private int port;
    private SmtpServer server;

    /**
     * Boots benchmark SMTP server once per trial.
     */
    @Setup(Level.Trial)
    public void setupServer() {
        port = SmtpBenchmarkSupport.findFreePort();
        server = SmtpBenchmarkSupport.startServer(port);
    }

    /**
     * Stops benchmark SMTP server after trial.
     */
    @TearDown(Level.Trial)
    public void tearDownServer() {
        if (server != null) {
            SmtpBenchmarkSupport.stopServer(server);
        }
    }

    /**
     * Measures end-to-end SMTP throughput under concurrent clients.
     */
    @Benchmark
    @Threads(8)
    @Fork(1)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 2)
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public int smtpTransactionThroughput() {
        SmtpBenchmarkSupport.sendMessage("127.0.0.1", port, bodyBytes);
        return 1;
    }

    /**
     * Measures end-to-end SMTP transaction latency under concurrent clients.
     */
    @Benchmark
    @Threads(8)
    @Fork(1)
    @Warmup(iterations = 3, time = 1)
    @Measurement(iterations = 5, time = 2)
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public long smtpTransactionLatency() {
        return SmtpBenchmarkSupport.sendMessage("127.0.0.1", port, bodyBytes);
    }
}
