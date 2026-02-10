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
 * SMTP 서버의 주기적 유지보수 작업을 스케줄링합니다.
 *
 * @property scope 작업 실행 코루틴 스코프
 * @property certChainFile 인증서 체인 파일
 * @property privateKeyFile 개인키 파일
 * @property onCertificateChanged 인증서 변경 감지 시 실행할 콜백
 * @property onRateLimiterCleanup 레이트리미터 정리 콜백
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
     * 유지보수 작업을 시작합니다.
     */
    fun start() {
        scheduleCertificateReload()
        scheduleRateLimiterCleanup()
    }

    /**
     * 등록된 유지보수 작업을 중지합니다.
     */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /**
     * 인증서 파일 변경을 감시해 TLS 컨텍스트 재로드 콜백을 호출합니다.
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
     * 레이트리미터 정리 작업을 주기적으로 실행합니다.
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
     * 파일 수정 시간을 epoch millis로 반환합니다.
     *
     * @param file 조회 대상 파일
     * @return 수정 시간(ms), 실패 시 0
     */
    private fun fileTimestamp(file: File): Long = runCatching {
        Files.getLastModifiedTime(file.toPath()).toMillis()
    }.getOrDefault(0L)
}
