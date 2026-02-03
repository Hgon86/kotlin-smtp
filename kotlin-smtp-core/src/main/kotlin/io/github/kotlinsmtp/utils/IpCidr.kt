package io.github.kotlinsmtp.utils

import java.net.InetAddress

/**
 * IP CIDR(예: 10.0.0.0/8, 192.168.0.1/32, ::1/128) 매칭 유틸.
 *
 * - PROXY protocol은 스푸핑 위험이 있으므로 "신뢰 프록시 IP 대역" 검사에 사용합니다.
 * - 기능 우선: IPv4/IPv6 공통 지원, 최소한의 비교 로직만 제공합니다.
 */
internal class IpCidr private constructor(
    private val network: ByteArray,
    private val prefixBits: Int,
) {
    fun contains(address: InetAddress): Boolean {
        val addr = address.address
        if (addr.size != network.size) return false // IPv4/IPv6 혼합 비교 방지

        var bitsRemaining = prefixBits
        var i = 0
        while (i < addr.size && bitsRemaining > 0) {
            val mask = when {
                bitsRemaining >= 8 -> 0xFF
                else -> (0xFF shl (8 - bitsRemaining)) and 0xFF
            }
            if ((network[i].toInt() and mask) != (addr[i].toInt() and mask)) return false
            bitsRemaining -= 8
            i++
        }
        return true
    }

    companion object {
        fun parse(raw: String): IpCidr? = runCatching {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val (ipPart, prefixPart) = trimmed.split("/", limit = 2).let { parts ->
                parts[0] to parts.getOrNull(1)
            }
            val addr = InetAddress.getByName(ipPart)
            val maxBits = addr.address.size * 8
            val prefix = (prefixPart?.toIntOrNull() ?: maxBits).coerceIn(0, maxBits)

            // 네트워크 주소로 정규화(마스크 적용)
            val network = addr.address.clone()
            var bitsRemaining = prefix
            for (i in network.indices) {
                val mask = when {
                    bitsRemaining >= 8 -> 0xFF
                    bitsRemaining <= 0 -> 0x00
                    else -> (0xFF shl (8 - bitsRemaining)) and 0xFF
                }
                network[i] = (network[i].toInt() and mask).toByte()
                bitsRemaining -= 8
            }
            IpCidr(network = network, prefixBits = prefix)
        }.getOrNull()
    }
}
