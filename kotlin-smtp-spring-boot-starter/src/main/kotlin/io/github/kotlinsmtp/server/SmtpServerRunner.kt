package io.github.kotlinsmtp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener

private val log = KotlinLogging.logger {}

class SmtpServerRunner(
    private val smtpServers: List<SmtpServer>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @EventListener
    fun onApplicationReady(event: ApplicationReadyEvent) {
        smtpServers.forEach { server ->
            log.info { "Starting SMTP server on port ${server.port} (implicitTls=${server.implicitTls})" }
            scope.launch { server.start() }
        }
    }

    @EventListener
    fun onContextClosed(event: ContextClosedEvent) = runBlocking {
        log.info { "Stopping SMTP server" }
        smtpServers.forEach { it.stop() }
        scope.cancel()
    }

    @PreDestroy
    fun destroy() {
        scope.cancel()
    }
}
