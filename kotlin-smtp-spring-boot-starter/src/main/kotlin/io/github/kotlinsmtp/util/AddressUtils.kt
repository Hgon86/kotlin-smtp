package io.github.kotlinsmtp.util

import java.net.IDN

/**
 * Starter-local address utilities.
 *
 * Note: core keeps address parsing/normalization internal to avoid expanding the stable public API surface.
 * Starter duplicates the minimal IDN normalization needed for outbound relay and policy checks.
 */
object AddressUtils {
    fun normalizeDomain(domain: String): String? = runCatching {
        val d = domain.trim().trimEnd('.')
        if (d.isEmpty()) return null
        IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).lowercase()
    }.getOrNull()

    fun normalizeDomainInAddress(address: String): String {
        val trimmed = address.trim()
        val at = trimmed.lastIndexOf('@')
        if (at <= 0 || at >= trimmed.lastIndex) return trimmed
        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        val normalized = normalizeDomain(domain) ?: domain
        return "$local@$normalized"
    }
}
