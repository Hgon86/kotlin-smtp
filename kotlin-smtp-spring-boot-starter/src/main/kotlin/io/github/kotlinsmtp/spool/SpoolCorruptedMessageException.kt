package io.github.kotlinsmtp.spool

/**
 * Exception used when raw/meta consistency of a spool message is broken.
 */
class SpoolCorruptedMessageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
