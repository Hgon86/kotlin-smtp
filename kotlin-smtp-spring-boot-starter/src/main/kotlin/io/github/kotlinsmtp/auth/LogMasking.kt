package io.github.kotlinsmtp.auth

/**
 * Log-safe masking utilities for authentication-related identifiers.
 *
 * Centralizes masking logic used across auth services and rate limiters
 * to ensure consistent PII reduction in log output.
 */

/**
 * Masks a username so that only the first two characters are shown.
 *
 * @return masked identifier, or `"unknown"` for blank input
 */
internal fun maskIdentity(value: String): String {
    if (value.isBlank()) return "unknown"
    if (value.length <= 2) return "**"
    return "${value.take(2)}***"
}

/**
 * Masks a client IP address, retaining only the first two octets/groups.
 *
 * @return masked IP string, or `"unknown"` for null/blank input
 */
internal fun maskIp(value: String?): String {
    val ip = value?.trim().orEmpty()
    if (ip.isEmpty()) return "unknown"
    val v4 = ip.split('.')
    if (v4.size == 4) return "${v4[0]}.${v4[1]}.*.*"
    val v6 = ip.split(':').filter { it.isNotEmpty() }
    if (v6.size >= 2) return "${v6[0]}:${v6[1]}:*"
    return "masked"
}
