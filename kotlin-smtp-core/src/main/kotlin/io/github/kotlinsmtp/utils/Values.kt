package io.github.kotlinsmtp.utils

internal object Values {
    val whitespaceRegex = Regex("\\s+")
    const val MAX_MESSAGE_SIZE = 52_428_800 // 50MB in bytes
    const val MAX_RECIPIENTS = 100 // Maximum RCPT count per session
    const val MAX_COMMAND_LINE_LENGTH = 2048 // RFC 5321 recommends 512; allow ESMTP extensions

    /**
     * Maximum length of SMTP line (command line / DATA line)
     *
     * - Upper bound when framing by CRLF in Netty inbound.
     * - DATA body lines can be longer than RFC 5322 recommendations, so use an operationally reasonable upper bound.
     */
    const val MAX_SMTP_LINE_LENGTH = 8192

    /**
     * Maximum BDAT (CHUNKING) chunk size
     *
     * - BDAT reads exactly N bytes into memory buffer, so a chunk upper bound is required.
     * - Total message size limit is applied separately by [MAX_MESSAGE_SIZE].
     */
    const val MAX_BDAT_CHUNK_SIZE = 8 * 1024 * 1024 // 8MB

    /**
     * Total in-flight (queued) BDAT byte-frame limit (bytes)
     *
     * - Current implementation buffers BDAT chunks as ByteArray, then forwards through coroutine channel.
     * - Frame count (capacity) limits alone are insufficient for large chunks,
     *   so define a separate upper bound for "total queued bytes".
     * - Default: [MAX_BDAT_CHUNK_SIZE] * 2
     */
    const val MAX_INFLIGHT_BDAT_BYTES: Int = MAX_BDAT_CHUNK_SIZE * 2
}
