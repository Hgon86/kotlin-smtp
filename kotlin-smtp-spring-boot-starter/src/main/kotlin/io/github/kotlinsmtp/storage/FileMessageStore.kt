package io.github.kotlinsmtp.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * File-based MessageStore implementation (feature-first).
 *
 * - In this project, the spooler copies/queues again after receipt, so this acts as temporary storage.
 * - TODO(storage): replace this implementation when moving to final storage (S3/DB).
 */
class FileMessageStore(
    private val tempDir: Path,
    private val copyBufferSize: Int = 64 * 1024,
) : MessageStore {
    override suspend fun storeRfc822(messageId: String, receivedHeaderValue: String, rawInput: InputStream): Path =
        withContext(Dispatchers.IO) {
            Files.createDirectories(tempDir)

            // Prevent name collisions with timestamp(epoch ms)+messageId.
            val targetFile = tempDir.resolve("mail_${Instant.now().toEpochMilli()}_${messageId}.eml")

            runCatching {
                BufferedOutputStream(Files.newOutputStream(targetFile)).use { out ->
                    // SMTP headers are ASCII-capable, but write safely as UTF-8.
                    out.write("Received: $receivedHeaderValue\r\n".toByteArray(StandardCharsets.UTF_8))
                    rawInput.copyTo(out, copyBufferSize)
                    out.flush()
                }
            }.onFailure { e ->
                runCatching { Files.deleteIfExists(targetFile) }
                throw e
            }

            log.debug { "Stored incoming message to temp file: $targetFile" }
            targetFile
        }
}

