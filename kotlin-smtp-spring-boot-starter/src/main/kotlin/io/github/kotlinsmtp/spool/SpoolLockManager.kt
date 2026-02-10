package io.github.kotlinsmtp.spool

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readText

private val lockLog = KotlinLogging.logger {}

/**
 * 스풀 메시지 파일(.eml)에 대한 파일 락(.lock) 관리를 담당합니다.
 *
 * @property spoolDir 스풀 디렉터리
 * @property staleLockThreshold 고아/stale 락 정리 임계값
 */
internal class SpoolLockManager(
    private val spoolDir: Path,
    private val staleLockThreshold: Duration,
) {
    /**
     * 대상 메시지 파일에 대한 락 획득을 시도합니다.
     *
     * @param rawPath 대상 메시지 파일 경로
     * @return 락 획득 성공 여부
     */
    fun tryLock(rawPath: Path): Boolean {
        val lock = lockPath(rawPath)
        return try {
            Files.writeString(
                lock,
                Instant.now().toEpochMilli().toString(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            true
        } catch (_: FileAlreadyExistsException) {
            false
        } catch (e: Exception) {
            lockLog.warn(e) { "Failed to create spool lock: $lock" }
            false
        }
    }

    /**
     * 대상 메시지 파일의 락을 해제합니다.
     *
     * @param rawPath 대상 메시지 파일 경로
     */
    fun unlock(rawPath: Path) {
        runCatching { Files.deleteIfExists(lockPath(rawPath)) }
    }

    /**
     * 스풀 디렉터리의 고아/stale 락을 정리합니다.
     */
    fun purgeOrphanedLocks() {
        val now = Instant.now()
        spoolDir.listDirectoryEntries("*.lock").forEach { lock ->
            val emlPath = lock.resolveSibling(lock.fileName.toString().replace(".lock", ".eml"))
            if (emlPath.notExists()) {
                runCatching { Files.deleteIfExists(lock) }
                return@forEach
            }

            val timestampMs = runCatching { lock.readText().toLong() }.getOrDefault(0L)
            if (timestampMs <= 0) return@forEach

            val age = Duration.ofMillis(now.toEpochMilli() - timestampMs)
            if (age > staleLockThreshold) {
                lockLog.warn { "Removing stale lock: $lock" }
                runCatching { Files.deleteIfExists(lock) }
            }
        }
    }

    private fun lockPath(rawPath: Path): Path =
        rawPath.resolveSibling(rawPath.fileName.toString().replace(".eml", ".lock"))
}
