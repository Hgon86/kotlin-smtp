package io.github.kotlinsmtp.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

private val sentStoreLog = KotlinLogging.logger {}

/**
 * File-based Sent mailbox storage.
 *
 * Stores submitted raw messages under `mailboxDir/<sender>/sent/`.
 *
 * @property mailboxDir mailbox root directory
 */
class FileSentMessageStore(
    private val mailboxDir: Path,
) : SentMessageStore {
    /**
     * Archives a submitted message into the sender's Sent mailbox.
     *
     * Skips archiving when sender identity is empty.
     *
     * @param rawPath raw RFC822 file path
     * @param envelopeSender envelope sender
     * @param submittingUser authenticated submitting user identifier
     * @param recipients recipient list
     * @param messageId message identifier
     * @param authenticated whether the session is authenticated
     */
    override fun archiveSubmittedMessage(
        rawPath: Path,
        envelopeSender: String?,
        submittingUser: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
    ) {
        val owner = if (authenticated) {
            submittingUser
        } else {
            envelopeSender?.substringBeforeLast('@')
        }

        val senderMailbox = owner
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        val root = mailboxDir.toAbsolutePath().normalize()
        val userDir = root.resolve(sanitizeMailboxOwner(senderMailbox)).resolve("sent").normalize()
        if (!userDir.startsWith(root)) {
            throw IllegalArgumentException("Sent mailbox path escape attempt")
        }

        Files.createDirectories(userDir)
        val file = userDir.resolve("sent_${Instant.now().toEpochMilli()}_${messageId}.eml")
        Files.copy(rawPath, file, StandardCopyOption.REPLACE_EXISTING)
        sentStoreLog.info {
            "Archived submitted message: sender=$senderMailbox recipients=${recipients.size} messageId=$messageId file=${file.fileName}"
        }
    }

    /**
     * Normalizes Sent mailbox owner identifier into a filesystem-safe token.
     *
     * @param owner original owner identifier
     * @return filesystem-safe token
     */
    private fun sanitizeMailboxOwner(owner: String): String {
        val sanitized = owner.map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '+' -> ch
                else -> '_'
            }
        }.joinToString("").take(128)

        require(sanitized.isNotBlank()) { "Invalid sent mailbox owner" }
        require(sanitized != "." && sanitized != "..") { "Invalid sent mailbox owner" }
        return sanitized
    }
}
