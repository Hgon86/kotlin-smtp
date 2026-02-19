package io.github.kotlinsmtp.relay.jakarta

import jakarta.mail.internet.MimeMessage
import java.util.Date
import java.util.UUID

/**
 * Supplements outbound message headers required for broad SMTP interoperability.
 *
 * Adds `Date` and `Message-ID` when missing.
 */
internal object OutboundMessageHeaderSupplement {
    internal data class Result(
        val dateAdded: Boolean,
        val messageIdAdded: Boolean,
    )

    /**
     * Ensures `Date` and `Message-ID` headers exist on outbound message.
     *
     * @param message outbound MIME message
     * @param envelopeSender SMTP envelope sender
     * @return supplementation result
     */
    fun ensureRequiredHeaders(message: MimeMessage, envelopeSender: String?): Result {
        val missingDate = !hasHeader(message, "Date")
        val missingMessageId = !hasHeader(message, "Message-ID")

        if (missingDate) {
            message.sentDate = Date()
        }
        if (missingMessageId) {
            val senderDomain = envelopeSender
                ?.substringAfterLast('@')
                ?.takeIf { it.isNotBlank() }
                ?.let { AddressUtils.normalizeDomain(it) ?: it.lowercase() }
                ?: "localhost"
            message.setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
        }

        return Result(
            dateAdded = missingDate,
            messageIdAdded = missingMessageId,
        )
    }

    private fun hasHeader(message: MimeMessage, name: String): Boolean =
        message.getHeader(name)?.any { it.isNotBlank() } == true
}
