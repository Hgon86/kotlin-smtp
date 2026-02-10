package io.github.kotlinsmtp.server

/**
 * 스풀 트리거 요청 결과입니다.
 */
public enum class SpoolTriggerResult {
    /** 트리거 요청이 정상 접수되었습니다. */
    ACCEPTED,

    /** 요청 인자가 유효하지 않아 거부되었습니다. */
    INVALID_ARGUMENT,

    /** 스풀러가 현재 요청을 처리할 수 없는 상태입니다. */
    UNAVAILABLE,
}

/**
 * 스풀/딜리버리 처리를 트리거하기 위한 최소 훅입니다.
 *
 * - 코어는 저장/릴레이 구현을 포함하지 않으며, 호스트가 구현체를 제공합니다.
 * - `tryTriggerOnce`는 non-blocking 계약이며, 실제 처리는 비동기 워커에서 수행될 수 있습니다.
 */
public interface SmtpSpooler {
    /**
     * 스풀 처리를 즉시 1회 트리거합니다.
     *
     * @return 요청 접수 여부
     */
    public fun tryTriggerOnce(): SpoolTriggerResult = runCatching {
        triggerOnce()
        SpoolTriggerResult.ACCEPTED
    }.getOrElse {
        SpoolTriggerResult.UNAVAILABLE
    }

    /**
     * 기존 호환용 트리거 메서드입니다.
     */
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
    public fun tryTriggerOnce(domain: String): SpoolTriggerResult = runCatching {
        triggerOnce(domain)
        SpoolTriggerResult.ACCEPTED
    }.getOrElse {
        SpoolTriggerResult.UNAVAILABLE
    }

    /**
     * 기존 호환용 도메인 트리거 메서드입니다.
     *
     * @param domain ETRN 인자로 정규화된 ASCII 도메인
     */
    public fun triggerOnce(domain: String): Unit
}
