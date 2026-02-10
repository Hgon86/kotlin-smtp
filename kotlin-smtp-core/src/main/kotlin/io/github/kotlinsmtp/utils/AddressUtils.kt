package io.github.kotlinsmtp.utils

import java.net.IDN

internal object AddressUtils {
    private val asciiLocalPartRegex = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}$")

    // RFC 1035-ish label validation; not a full RFC 5322 parser.
    private val asciiDomainRegex = Regex(
        "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
    )

    /**
     * 문자열에서 괄호로 둘러싸인 내용 추출
     */
    fun extractFromBrackets(string: String, openingBracket: String = "<", closingBracket: String = ">"): String? =
        string.indexOf(openingBracket).takeIf { it >= 0 }?.let { fromIndex ->
            string.lastIndexOf(closingBracket).takeIf { it > fromIndex }?.let { toIndex ->
                string.slice(fromIndex + 1 until toIndex)
            }
        }

    /**
     * 이메일 주소 검증
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
     * SMTPUTF8 주소(UTF-8 local-part) 최소 검증
     *
     * - RFC 전체를 엄밀히 구현하기보다, "실사용에서 터지지 않게" 보수적으로 검증합니다.
     * - 도메인은 IDNA(Punycode)로 변환 가능해야 합니다.
     */
    fun validateSmtpUtf8Address(address: String): Boolean = runCatching {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        // 헤더/프로토콜 파손 방지
        if (trimmed.any { it == '\r' || it == '\n' }) return false
        val at = trimmed.indexOf('@')
        if (at <= 0) return false
        if (at != trimmed.lastIndexOf('@')) return false
        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        if (domain.isEmpty()) return false
        // local-part는 whitespace/제어문자만 막고 나머지는 허용(UTF-8)
        if (local.any { it.isWhitespace() || it.code < 0x20 }) return false
        // 도메인은 IDN 변환 가능해야 함
        normalizeDomain(domain) != null
    }.getOrDefault(false)

    /**
     * 도메인을 비교/정책 판단용으로 정규화합니다.
     * - Unicode 도메인 → IDNA ASCII로 변환
     */
    fun normalizeDomain(domain: String): String? = runCatching {
        val d = domain.trim().trimEnd('.')
        if (d.isEmpty()) return null
        // ALLOW_UNASSIGNED: 운영상 보수적으로 허용. 필요하면 USE_STD3_ASCII_RULES 등으로 강화 가능.
        IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).lowercase()
    }.getOrNull()

    /**
     * 도메인을 정규화하고 유효성(라벨/길이 규칙)을 함께 확인합니다.
     */
    fun normalizeValidDomain(domain: String): String? {
        val normalized = normalizeDomain(domain) ?: return null
        if (!asciiDomainRegex.matches(normalized)) return null
        return normalized
    }

    /**
     * 도메인 문자열이 정책상 허용 가능한 형태인지 확인합니다.
     */
    fun isValidDomain(domain: String): Boolean {
        return normalizeValidDomain(domain) != null
    }

    fun isAllAscii(value: String): Boolean = value.all { it.code in 0x20..0x7E }

    /**
     * 주소에서 도메인 부분만 IDNA(ASCII)로 정규화합니다.
     * - local-part는 그대로 둡니다(UTF-8 local-part는 SMTPUTF8에서 필요).
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
     * 호스트 부분 검증
     */
    fun validateHost(host: String): Boolean {
        if (!host.startsWith("@")) return false

        return runCatching {
            val domain = host.substring(1) // @ 제거
            val normalized = normalizeDomain(domain) ?: return false
            asciiDomainRegex.matches(normalized)
        }.getOrDefault(false)
    }

    /**
     * 이메일에서 도메인 부분 추출
     */
    fun extractDomain(email: String): String? =
        email.substringAfterLast('@', "").takeIf { it.isNotEmpty() }
}

/**
 * 문자열이 유효한 이메일 주소인지 확인하는 확장 함수
 */
internal fun String.isValidEmailAddress(): Boolean = AddressUtils.validateAddress(this)

/**
 * 문자열이 유효한 이메일 호스트인지 확인하는 확장 함수
 */
internal fun String.isValidEmailHost(): Boolean = AddressUtils.validateHost(this)
