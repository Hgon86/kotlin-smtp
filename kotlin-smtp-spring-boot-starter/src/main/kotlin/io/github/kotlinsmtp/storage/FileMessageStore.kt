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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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
                val rawBytes = rawInput.readBytes()
                val supplementHeaders = buildSupplementHeadersIfMissing(rawBytes)
                BufferedOutputStream(Files.newOutputStream(targetFile)).use { out ->
                    // SMTP headers are ASCII-capable, but write safely as UTF-8.
                    out.write("Received: $receivedHeaderValue\r\n".toByteArray(StandardCharsets.UTF_8))
                    if (supplementHeaders.isNotEmpty()) {
                        out.write(supplementHeaders.toByteArray(StandardCharsets.UTF_8))
                    }
                    out.write(rawBytes)
                    out.flush()
                }
            }.onFailure { e ->
                runCatching { Files.deleteIfExists(targetFile) }
                throw e
            }

            log.debug { "Stored incoming message to temp file: $targetFile" }
            targetFile
        }

    /**
     * Builds interoperability headers (`Date`, `Message-ID`) when missing in incoming RFC822.
     *
     * @param rawBytes incoming RFC822 bytes
     * @return CRLF-terminated header block to prepend, or empty string
     */
    private fun buildSupplementHeadersIfMissing(rawBytes: ByteArray): String {
        val headerSection = extractHeaderSection(rawBytes)
        val hasDate = hasHeader(headerSection, "Date")
        val hasMessageId = hasHeader(headerSection, "Message-ID")
        if (hasDate && hasMessageId) return ""

        val sb = StringBuilder()
        if (!hasDate) {
            sb.append("Date: ")
                .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
                .append("\r\n")
        }
        if (!hasMessageId) {
            val senderDomain = extractSenderDomain(headerSection) ?: "localhost"
            sb.append("Message-ID: <")
                .append(UUID.randomUUID())
                .append('@')
                .append(senderDomain)
                .append(">\r\n")
        }
        return sb.toString()
    }

    private fun extractHeaderSection(rawBytes: ByteArray): String {
        val text = rawBytes.toString(StandardCharsets.ISO_8859_1)
        val crlfIndex = text.indexOf("\r\n\r\n")
        if (crlfIndex >= 0) return text.substring(0, crlfIndex)
        val lfIndex = text.indexOf("\n\n")
        if (lfIndex >= 0) return text.substring(0, lfIndex)
        return text.take(16 * 1024)
    }

    private fun hasHeader(headerSection: String, headerName: String): Boolean =
        Regex("(?im)^${Regex.escape(headerName)}\\s*:").containsMatchIn(headerSection)

    private fun extractSenderDomain(headerSection: String): String? {
        val fromLine = Regex("(?im)^From\\s*:\\s*(.+)$")
            .find(headerSection)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null
        val at = fromLine.lastIndexOf('@')
        if (at < 0 || at == fromLine.lastIndex) return null
        val candidate = fromLine.substring(at + 1)
            .trim()
            .trimEnd('>', ')', ';', ',', '"', '\'', '\\')
            .substringBefore(' ')
            .substringBefore('>')
            .lowercase()
        return candidate.takeIf { it.isNotBlank() }
    }
}

