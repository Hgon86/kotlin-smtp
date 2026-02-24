package io.github.kotlinsmtp.utils

import java.net.IDN

internal object AddressUtils {
    private val asciiLocalPartRegex = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}$")

    // RFC 1035-ish label validation; not a full RFC 5322 parser.
    private val asciiDomainRegex = Regex(
        "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
    )

    /**
     * Extract content enclosed by brackets from string
     */
    fun extractFromBrackets(string: String, openingBracket: String = "<", closingBracket: String = ">"): String? =
        string.indexOf(openingBracket).takeIf { it >= 0 }?.let { fromIndex ->
            val start = fromIndex + openingBracket.length
            string.indexOf(closingBracket, startIndex = start).takeIf { it >= start }?.let { toIndex ->
                string.substring(start, toIndex)
            }
        }

    /**
     * Validate email address
     */
    fun validateAddress(address: String): Boolean = runCatching {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        // header/protocol injection guard
        if (trimmed.any { it == '\r' || it == '\n' }) return false

        val at = trimmed.indexOf('@')
        if (at <= 0) return false
        if (at != trimmed.lastIndexOf('@')) return false

        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        if (domain.isEmpty()) return false

        // This validator is used for the non-SMTPUTF8 path.
        // Keep it conservative: require ASCII local-part.
        if (!isAllAscii(local)) return false
        if (local.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return false
        if (!asciiLocalPartRegex.matches(local)) return false

        val normalizedDomain = normalizeDomain(domain) ?: return false
        if (!asciiDomainRegex.matches(normalizedDomain)) return false

        // RFC 5321 limit: 254 chars for mailbox is a common practical maximum.
        if ((local.length + 1 + normalizedDomain.length) > 254) return false

        true
    }.getOrDefault(false)

    /**
     * Minimal validation for SMTPUTF8 address (UTF-8 local-part)
     *
     * - Rather than strict full RFC implementation, validates conservatively for practical robustness.
     * - Domain must be convertible to IDNA (Punycode).
     */
    fun validateSmtpUtf8Address(address: String): Boolean = runCatching {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        // Prevent header/protocol corruption
        if (trimmed.any { it == '\r' || it == '\n' }) return false
        val at = trimmed.indexOf('@')
        if (at <= 0) return false
        if (at != trimmed.lastIndexOf('@')) return false
        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        if (domain.isEmpty()) return false
        // Allow UTF-8 local-part except whitespace/control characters
        if (local.any { it.isWhitespace() || it.code < 0x20 }) return false
        // Domain must be IDN-convertible
        normalizeDomain(domain) != null
    }.getOrDefault(false)

    /**
     * Normalize domain for comparison/policy checks.
     * - Convert Unicode domain to IDNA ASCII
     */
    fun normalizeDomain(domain: String): String? = runCatching {
        val d = domain.trim().trimEnd('.')
        if (d.isEmpty()) return null
        // ALLOW_UNASSIGNED: conservative operational allowance. Can be tightened with USE_STD3_ASCII_RULES if needed.
        IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).lowercase()
    }.getOrNull()

    /**
     * Normalize domain and validate label/length rules.
     */
    fun normalizeValidDomain(domain: String): String? {
        val normalized = normalizeDomain(domain) ?: return null
        if (!asciiDomainRegex.matches(normalized)) return null
        return normalized
    }

    /**
     * Check whether domain string is in policy-acceptable form.
     */
    fun isValidDomain(domain: String): Boolean {
        return normalizeValidDomain(domain) != null
    }

    fun isAllAscii(value: String): Boolean = value.all { it.code in 0x20..0x7E }

    /**
     * Normalize only domain part in address to IDNA (ASCII).
     * - Keep local-part unchanged (UTF-8 local-part is needed for SMTPUTF8).
     */
    fun normalizeDomainInAddress(address: String): String {
        val trimmed = address.trim()
        val at = trimmed.lastIndexOf('@')
        if (at <= 0 || at >= trimmed.lastIndex) return trimmed
        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        val normalized = normalizeDomain(domain) ?: domain
        return "$local@$normalized"
    }

    /**
     * Validate host part
     */
    fun validateHost(host: String): Boolean {
        if (!host.startsWith("@")) return false

        return runCatching {
            val domain = host.substring(1) // Remove @
            val normalized = normalizeDomain(domain) ?: return false
            asciiDomainRegex.matches(normalized)
        }.getOrDefault(false)
    }

    /**
     * Extract domain part from email
     */
    fun extractDomain(email: String): String? =
        email.substringAfterLast('@', "").takeIf { it.isNotEmpty() }
}

/**
 * Extension function to check whether string is a valid email address
 */
internal fun String.isValidEmailAddress(): Boolean = AddressUtils.validateAddress(this)

/**
 * Extension function to check whether string is a valid email host
 */
internal fun String.isValidEmailHost(): Boolean = AddressUtils.validateHost(this)
