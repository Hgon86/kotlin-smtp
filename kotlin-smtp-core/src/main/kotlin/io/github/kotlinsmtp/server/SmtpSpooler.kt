package io.github.kotlinsmtp.server

/**
 * 스풀/딜리버리 처리를 트리거하기 위한 최소 훅입니다.
 *
 * - 코어는 저장/릴레이 구현을 포함하지 않으며, 호스트가 구현체를 제공합니다.
 */
public interface SmtpSpooler {
    public fun triggerOnce(): Unit
}

/**
 * ETRN 도메인 인자를 반영할 수 있는 스풀러 확장 훅입니다.
 */
public interface SmtpDomainSpooler : SmtpSpooler {
    /**
     * 지정한 도메인에 대해 스풀 처리를 즉시 1회 트리거합니다.
     *
     * @param domain ETRN 인자로 정규화된 ASCII 도메인
     */
    public fun triggerOnce(domain: String): Unit
}
