package io.github.kotlinsmtp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

private val log = KotlinLogging.logger {}

class SmtpServerRunner(
    private val smtpServers: List<SmtpServer>,
    private val gracefulShutdownTimeoutMs: Long,
) {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "smtp-server-runner").apply { isDaemon = false }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stopped = AtomicBoolean(false)

    @EventListener
    fun onApplicationReady(@Suppress("UNUSED_PARAMETER") event: ApplicationReadyEvent) {
        smtpServers.forEach { server ->
            log.info { "Starting SMTP server on port ${server.port}" }
            scope.launch { server.start() }
        }
    }

    @EventListener
    fun onContextClosed(@Suppress("UNUSED_PARAMETER") event: ContextClosedEvent) = runBlocking {
        if (!stopped.compareAndSet(false, true)) return@runBlocking
        log.info { "Stopping SMTP server" }
        stopAllServers()
    }

    @PreDestroy
    fun destroy() = runBlocking {
        if (!stopped.compareAndSet(false, true)) return@runBlocking
        stopAllServers()
    }

    private suspend fun stopAllServers() {
        smtpServers.forEach { it.stop(gracefulTimeoutMs = gracefulShutdownTimeoutMs) }
        scope.cancel()
        dispatcher.close()
    }
}
