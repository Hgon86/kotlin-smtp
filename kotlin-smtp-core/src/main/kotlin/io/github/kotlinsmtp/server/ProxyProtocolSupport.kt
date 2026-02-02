package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.IpCidr
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * HAProxy PROXY protocol(v1) 지원을 위한 공통 유틸/채널 속성.
 *
 * - PROXY 헤더는 스푸핑 위험이 있으므로, "신뢰 프록시"에서 온 연결에만 허용해야 합니다.
 * - 본 프로젝트에서는 리스너별로 PROXY 사용 여부를 켜고, 신뢰 CIDR 목록으로 검증합니다.
 */
object ProxyProtocolSupport {
    /**
     * PROXY 헤더에서 복원한 "실제 클라이언트 주소"를 저장합니다.
     * - 없으면 일반 TCP remoteAddress를 사용합니다.
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
