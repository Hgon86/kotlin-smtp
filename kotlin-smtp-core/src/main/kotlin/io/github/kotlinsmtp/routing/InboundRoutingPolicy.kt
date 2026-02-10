package io.github.kotlinsmtp.routing

/**
 * 수신 메일의 로컬/외부 라우팅 정책 SPI.
 *
 * 기본 구현은 설정 파일 기반으로 동작하며,
 * 사용자는 DB나 외부 서비스 연동 등으로 교체할 수 있습니다.
 */
public fun interface InboundRoutingPolicy {

    /**
     * 주어진 수신자가 로컬 도메인인지 판단합니다.
     *
     * @param recipient 수신자 이메일 주소 (예: user@example.com)
     * @return 로컬 도메인이면 true, 외부 도메인이면 false
     */
    public fun isLocalDomain(recipient: String): Boolean

    /**
     * 현재 관리 중인 로컬 도메인 목록을 반환합니다.
     * (로깅, 모니터링, HELLO 응답 등에 활용)
     *
     * @return 로컬 도메인 집합
     */
    public fun localDomains(): Set<String> = emptySet()
}

/**
 * 단일 도메인 기반의 간단한 라우팅 정책.
 *
 * @property domain 로컬 도메인 (예: example.com)
 */
public class SingleDomainRoutingPolicy(
    private val domain: String
) : InboundRoutingPolicy {

    private val normalizedDomain = domain.trim().lowercase()

    override fun isLocalDomain(recipient: String): Boolean {
        val recipientDomain = recipient.substringAfterLast('@', "").trim().lowercase()
        return recipientDomain == normalizedDomain
    }

    override fun localDomains(): Set<String> = setOf(normalizedDomain)
}

/**
 * 다중 도메인 기반의 라우팅 정책.
 *
 * @property domains 로컬 도메인 목록
 */
public class MultiDomainRoutingPolicy(
    private val domains: Set<String>
) : InboundRoutingPolicy {

    private val normalizedDomains = domains.map { it.trim().lowercase() }.toSet()

    override fun isLocalDomain(recipient: String): Boolean {
        val recipientDomain = recipient.substringAfterLast('@', "").trim().lowercase()
        return recipientDomain in normalizedDomains
    }

    override fun localDomains(): Set<String> = normalizedDomains
}
