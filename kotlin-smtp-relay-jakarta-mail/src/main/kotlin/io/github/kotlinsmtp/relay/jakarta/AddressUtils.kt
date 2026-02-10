package io.github.kotlinsmtp.relay.jakarta

import java.net.IDN

/**
 * outbound relay에서 사용하는 도메인/주소 정규화 유틸.
 */
internal object AddressUtils {
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
