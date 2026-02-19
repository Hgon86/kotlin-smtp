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
 * Abstraction for spool message lock managers.
 */
interface SpoolLockManager {
    /**
     * Tries to acquire lock for target message file.
     *
     * @param rawPath target message file path
     * @return whether lock acquisition succeeded
     */
    fun tryLock(rawPath: Path): Boolean

    /**
     * Releases lock for target message file.
     *
     * @param rawPath target message file path
     */
    fun unlock(rawPath: Path)

    /**
     * Refreshes lock TTL for target message file.
     *
     * @param rawPath target message file path
     * @return whether refresh succeeded
     */
    fun refreshLock(rawPath: Path): Boolean = true

    /**
     * Purges orphan/stale locks in spool directory.
     */
    fun purgeOrphanedLocks()
}

/**
 * Manages file locks (`.lock`) for spool message files (`.eml`).
 *
 * @property spoolDir spool directory
 * @property staleLockThreshold threshold for orphan/stale lock cleanup
 */
class FileSpoolLockManager(
    private val spoolDir: Path,
    private val staleLockThreshold: Duration,
) : SpoolLockManager {
    /**
     * Tries to acquire lock for target message file.
     *
     * @param rawPath target message file path
     * @return whether lock acquisition succeeded
     */
    override fun tryLock(rawPath: Path): Boolean {
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
     * Releases lock for target message file.
     *
     * @param rawPath target message file path
     */
    override fun unlock(rawPath: Path) {
        runCatching { Files.deleteIfExists(lockPath(rawPath)) }
    }

    /**
     * File lock mode relies on stale cleanup, so TTL refresh is always treated as success.
     *
     * @param rawPath target message file path
     * @return always true
     */
    override fun refreshLock(rawPath: Path): Boolean = true

    /**
     * Purges orphan/stale locks in spool directory.
     */
    override fun purgeOrphanedLocks() {
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
