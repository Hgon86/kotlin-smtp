package io.github.kotlinsmtp.mail

/**
 * Outbound relay model.
 *
 * Kept in the starter because MX resolution is not part of the core SMTP server engine.
 */
data class MxRecord(val priority: Int, val host: String)
