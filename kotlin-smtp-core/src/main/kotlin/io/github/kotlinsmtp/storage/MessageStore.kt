package io.github.kotlinsmtp.storage

import java.io.InputStream
import java.nio.file.Path

/**
 * Boundary for storing raw messages (RFC 5322; typically .eml) received by SMTP.
 *
 * - Final storage backend (DB/S3, etc.) is not finalized yet, so only file-based implementation is currently provided.
 * - TODO(storage): keep this interface so it can be replaced with S3/DB for operations/scaling.
 */
public interface MessageStore {
    /**
     * Store SMTP body (rawInput) in "Received header + raw body" form.
     *
     * @param messageId Internal server transaction identifier (for logs/tracing)
     * @param receivedHeaderValue Received: header value (excluding header name)
     * @param rawInput Body stream from DATA/BDAT (byte-preserving)
     */
    public suspend fun storeRfc822(
        messageId: String,
        receivedHeaderValue: String,
        rawInput: InputStream,
    ): Path
}
