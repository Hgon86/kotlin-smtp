package io.github.kotlinsmtp.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

class LocalMailboxManager(
    private val mailboxDir: Path = Path.of("C:\\smtp-server\\mailboxes")
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val mailboxRoot: Path = mailboxDir.toAbsolutePath().normalize()

    init {
        try {
            Files.createDirectories(mailboxRoot)
            log.info { "Initialized mailbox directory at: $mailboxRoot" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create mailbox directory: $mailboxRoot" }
            throw RuntimeException("Failed to initialize mailbox directory", e)
        }
    }

    suspend fun deliverToLocalMailbox(username: String, tempFile: Path): Path = withContext(Dispatchers.IO) {
        // TODO(storage): introduce abstraction (MessageStore) so local file storage can be replaced with DB/S3/object storage.
        //               - Local disk has clear limitations in high-traffic/multi-instance environments.
        //               - Final goal: loosely separate "mail body storage" from "SMTP receive/relay".
        val sanitizedUsername = sanitizeUsername(username)
        val userMailbox = getUserMailboxDir(username)

        val timestamp = LocalDateTime.now().format(dateFormatter)
        val mailFile = userMailbox.resolve("mail_${timestamp}.eml")

        try {
            Files.copy(tempFile, mailFile, StandardCopyOption.REPLACE_EXISTING)
            log.info { "Mail delivered to $sanitizedUsername's mailbox: ${mailFile.fileName}" }
            mailFile
        } catch (e: Exception) {
            log.error(e) { "Failed to deliver mail to $sanitizedUsername's mailbox" }
            throw e
        }
    }

    private fun getUserMailboxDir(username: String): Path {
        val sanitizedUsername = sanitizeUsername(username)
        val userMailbox = mailboxRoot.resolve(sanitizedUsername).normalize()
        if (!userMailbox.startsWith(mailboxRoot)) {
            throw IllegalArgumentException("Mailbox path escape attempt")
        }
        try {
            Files.createDirectories(userMailbox)
            log.debug { "Ensured mailbox directory for user: $sanitizedUsername" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create mailbox directory for user: $sanitizedUsername" }
            throw RuntimeException("Failed to create user mailbox directory", e)
        }
        return userMailbox
    }

    private fun sanitizeUsername(username: String): String {
        val u = username.trim()
        if (u.isEmpty()) throw IllegalArgumentException("Username is blank")

        // Restrict very conservatively because this may be used as filesystem path.
        // - Dot (.) is allowed, but "."/".." itself is disallowed (normalization can escape root).
        val sanitized = u.map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '+' -> ch
                else -> '_'
            }
        }.joinToString("").take(128)

        if (sanitized == "." || sanitized == "..") {
            throw IllegalArgumentException("Invalid username")
        }
        if (sanitized.isBlank()) throw IllegalArgumentException("Invalid username")
        return sanitized
    }
}
