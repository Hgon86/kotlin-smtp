package io.github.kotlinsmtp.storage

import java.nio.file.Path

/**
 * Boundary for storing outgoing (sent mailbox) raw messages.
 */
interface SentMessageStore {
    /**
     * Record outgoing message into sent-mailbox store.
     *
     * @param rawPath Raw RFC822 file path
     * @param envelopeSender Envelope sender
     * @param submittingUser Authenticated submitting user identifier
     * @param recipients Recipient list
     * @param messageId Message identifier
     * @param authenticated Whether session is authenticated
     */
    fun archiveSubmittedMessage(
        rawPath: Path,
        envelopeSender: String?,
        submittingUser: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
    )
}
