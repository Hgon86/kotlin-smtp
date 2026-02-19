package io.github.kotlinsmtp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.time.Duration

private val maintenanceLog = KotlinLogging.logger {}

/**
 * Schedules periodic maintenance tasks for SMTP server.
 *
 * @property scope Coroutine scope for task execution
 * @property certChainFile Certificate chain file
 * @property privateKeyFile Private key file
 * @property onCertificateChanged Callback when certificate changes are detected
 * @property onRateLimiterCleanup Callback for rate-limiter cleanup
 */
internal class SmtpServerMaintenanceScheduler(
    private val scope: CoroutineScope,
    private val certChainFile: File?,
    private val privateKeyFile: File?,
    private val onCertificateChanged: () -> Unit,
    private val onRateLimiterCleanup: () -> Unit,
) {
    private val jobs: MutableList<Job> = mutableListOf()

    /**
     * Start maintenance tasks.
     */
    fun start() {
        scheduleCertificateReload()
        scheduleRateLimiterCleanup()
    }

    /**
     * Stop registered maintenance tasks.
     */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /**
     * Watch certificate file changes and invoke TLS context reload callback.
     */
    private fun scheduleCertificateReload() {
        if (certChainFile == null || privateKeyFile == null) return

        val job = scope.launch {
            var lastChainTime = fileTimestamp(certChainFile)
            var lastKeyTime = fileTimestamp(privateKeyFile)

            while (scope.isActive) {
                delay(Duration.ofMinutes(5).toMillis())
                val currentChainTime = fileTimestamp(certChainFile)
                val currentKeyTime = fileTimestamp(privateKeyFile)

                if (currentChainTime > lastChainTime || currentKeyTime > lastKeyTime) {
                    maintenanceLog.info { "Detected TLS certificate change, reloading." }
                    onCertificateChanged()
                    lastChainTime = currentChainTime
                    lastKeyTime = currentKeyTime
                }
            }
        }
        jobs.add(job)
    }

    /**
     * Run rate-limiter cleanup task periodically.
     */
    private fun scheduleRateLimiterCleanup() {
        val job = scope.launch {
            while (scope.isActive) {
                delay(Duration.ofHours(1).toMillis())
                onRateLimiterCleanup()
                maintenanceLog.debug { "Rate limiter cleanup completed" }
            }
        }
        jobs.add(job)
    }

    /**
     * Return file last-modified time in epoch millis.
     *
     * @param file Target file to query
     * @return Last-modified time (ms), or 0 on failure
     */
    private fun fileTimestamp(file: File): Long = runCatching {
        Files.getLastModifiedTime(file.toPath()).toMillis()
    }.getOrDefault(0L)
}
