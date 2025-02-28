package com.crinity.kotlinsmtp.server

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
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class SmtpServerRunner(
    private val smtpServer: SmtpServer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @EventListener
    fun onApplicationReady(event: ApplicationReadyEvent) {
        log.info { "Starting SMTP server on port ${smtpServer.port}" }
        scope.launch {
            smtpServer.start()
        }
    }

    @EventListener
    fun onContextClosed(event: ContextClosedEvent) = runBlocking {
        log.info { "Stopping SMTP server" }
        smtpServer.stop()
        scope.cancel()
    }

    @PreDestroy
    fun destroy() {
        scope.cancel()
    }
}