package io.github.kotlinsmtp.utils

import java.net.InetAddress

/**
 * IP CIDR (e.g., 10.0.0.0/8, 192.168.0.1/32, ::1/128) matching utility.
 *
 * - PROXY protocol has spoofing risk, so this is used to check "trusted proxy IP ranges".
 * - Feature-first: supports both IPv4/IPv6 with minimal comparison logic.
 */
internal class IpCidr private constructor(
    private val network: ByteArray,
    private val prefixBits: Int,
) {
    fun contains(address: InetAddress): Boolean {
        val addr = address.address
        if (addr.size != network.size) return false // Prevent mixed IPv4/IPv6 comparison

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

            // Normalize to network address (apply mask)
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
