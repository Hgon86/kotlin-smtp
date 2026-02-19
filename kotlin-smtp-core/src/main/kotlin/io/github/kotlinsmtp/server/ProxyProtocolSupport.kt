package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.IpCidr
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Common utility/channel attributes for HAProxy PROXY protocol (v1) support.
 *
 * - PROXY headers have spoofing risk, so they must be allowed only for connections from trusted proxies.
 * - This project enables PROXY per listener and validates with trusted CIDR list.
 */
internal object ProxyProtocolSupport {
    /**
     * Stores "actual client address" restored from PROXY header.
     * - Uses normal TCP remoteAddress when absent.
     */
    val REAL_PEER: AttributeKey<InetSocketAddress> = AttributeKey.valueOf("smtp.realPeer")

    fun effectiveRemoteSocketAddress(channel: Channel): InetSocketAddress? {
        return channel.attr(REAL_PEER).get() ?: (channel.remoteAddress() as? InetSocketAddress)
    }

    fun effectiveClientIp(channel: Channel): String? {
        return effectiveRemoteSocketAddress(channel)?.address?.hostAddress
            ?: effectiveRemoteSocketAddress(channel)?.hostString
    }

    fun isTrustedProxy(remote: InetSocketAddress?, trustedCidrs: List<IpCidr>): Boolean {
        if (remote == null) return false
        val addr: InetAddress = remote.address ?: return false
        if (trustedCidrs.isEmpty()) return false
        return trustedCidrs.any { it.contains(addr) }
    }
}
