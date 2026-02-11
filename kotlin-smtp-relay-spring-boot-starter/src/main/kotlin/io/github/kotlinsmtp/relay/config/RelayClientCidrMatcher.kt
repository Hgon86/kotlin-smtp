package io.github.kotlinsmtp.relay.config

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 릴레이 제출 클라이언트 IP의 CIDR allowlist 매칭기입니다.
 *
 * @property cidrs 허용 CIDR 목록
 */
internal class RelayClientCidrMatcher(
    cidrs: List<String>,
) {
    private val networks = cidrs.map { CidrNetwork.parse(it) }

    /**
     * 클라이언트 주소가 허용 CIDR에 포함되는지 검사합니다.
     *
     * CIDR 목록이 비어있으면 true를 반환합니다.
     *
     * @param peerAddress 세션 클라이언트 주소 문자열
     * @return 허용 여부
     */
    fun isAllowed(peerAddress: String?): Boolean {
        if (networks.isEmpty()) return true
        val ip = extractIp(peerAddress) ?: return false
        return networks.any { it.contains(ip) }
    }

    private fun extractIp(peerAddress: String?): InetAddress? {
        val raw = peerAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val bracketed = if (raw.startsWith("[") && raw.contains("]")) {
            raw.substringAfter('[').substringBefore(']')
        } else null

        val direct = parseInetLiteral(raw)
        if (direct != null) return direct

        val fromBracket = bracketed?.let { parseInetLiteral(it) }
        if (fromBracket != null) return fromBracket

        val ipv4Host = raw.substringBefore(':')
        return parseInetLiteral(ipv4Host)
    }

    private fun parseInetLiteral(value: String): InetAddress? {
        val trimmed = value.trim()
        if (!looksLikeIpLiteral(trimmed)) return null
        return runCatching { InetAddress.getByName(trimmed) }.getOrNull()
    }

    private fun looksLikeIpLiteral(value: String): Boolean {
        if (value.isEmpty()) return false
        val ipv4 = value.all { it.isDigit() || it == '.' }
        if (ipv4) return true

        val ipv6 = value.contains(':') && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        return ipv6
    }

    private data class CidrNetwork(
        val family: Family,
        val networkBytes: ByteArray,
        val prefixBits: Int,
    ) {
        fun contains(address: InetAddress): Boolean {
            val candidateFamily = when (address) {
                is Inet4Address -> Family.IPV4
                is Inet6Address -> Family.IPV6
                else -> return false
            }
            if (candidateFamily != family) return false

            val candidate = address.address
            var bits = prefixBits
            for (i in networkBytes.indices) {
                if (bits <= 0) break
                val checkBits = minOf(8, bits)
                val mask = (0xFF shl (8 - checkBits)) and 0xFF
                val left = networkBytes[i].toInt() and mask
                val right = candidate[i].toInt() and mask
                if (left != right) return false
                bits -= checkBits
            }
            return true
        }

        companion object {
            fun parse(raw: String): CidrNetwork {
                val cidr = raw.trim()
                require(cidr.isNotEmpty()) { "CIDR must not be blank" }
                val host = cidr.substringBefore('/').trim()
                val prefixText = cidr.substringAfter('/', "").trim()
                require(host.isNotEmpty() && prefixText.isNotEmpty()) { "CIDR must be in host/prefix format: $raw" }

                val address = parseLiteralAddress(host)
                val family = when (address) {
                    is Inet4Address -> Family.IPV4
                    is Inet6Address -> Family.IPV6
                    else -> throw IllegalArgumentException("Unsupported IP address: $raw")
                }

                val maxBits = if (family == Family.IPV4) 32 else 128
                val prefix = prefixText.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid CIDR prefix: $raw")
                require(prefix in 0..maxBits) { "CIDR prefix out of range: $raw" }

                return CidrNetwork(
                    family = family,
                    networkBytes = address.address,
                    prefixBits = prefix,
                )
            }

            private fun parseLiteralAddress(host: String): InetAddress {
                require(host.isNotBlank()) { "CIDR host must not be blank" }
                val isLiteral = host.all { it.isDigit() || it == '.' } ||
                    (host.contains(':') && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' })
                require(isLiteral) { "CIDR host must be an IP literal: $host" }
                return InetAddress.getByName(host)
            }
        }
    }

    private enum class Family {
        IPV4,
        IPV6,
    }
}
