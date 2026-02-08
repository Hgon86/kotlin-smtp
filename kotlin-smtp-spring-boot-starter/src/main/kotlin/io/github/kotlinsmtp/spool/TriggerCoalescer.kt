package io.github.kotlinsmtp.spool

/**
 * 스풀 트리거 요청을 병합(coalescing)하기 위한 큐입니다.
 *
 * 규칙:
 * - 전체 트리거(Full)는 도메인 트리거보다 우선하며, 기존 도메인 트리거를 흡수합니다.
 * - 도메인 트리거는 중복 도메인을 1개로 병합합니다.
 */
internal class TriggerCoalescer {
    private var fullPending = false
    private val domains = linkedSetOf<String>()

    /**
     * 트리거 요청을 큐에 반영합니다.
     *
     * @param domain null이면 전체 트리거, 값이 있으면 도메인 트리거
     */
    fun submit(domain: String?) {
        when (domain) {
            null -> {
                fullPending = true
                domains.clear()
            }
            else -> {
                if (!fullPending) {
                    domains.add(domain)
                }
            }
        }
    }

    /**
     * 다음 실행 대상을 반환합니다.
     *
     * @return 실행할 트리거가 없으면 null
     */
    fun poll(): SpoolTrigger? {
        if (fullPending) {
            fullPending = false
            domains.clear()
            return SpoolTrigger.Full
        }
        if (domains.isNotEmpty()) {
            val domain = domains.first()
            domains.remove(domain)
            return SpoolTrigger.Domain(domain)
        }
        return null
    }
}

/**
 * 스풀 트리거 실행 단위를 표현합니다.
 */
internal sealed interface SpoolTrigger {
    /** 전체 큐 트리거 */
    data object Full : SpoolTrigger

    /**
     * 도메인 한정 트리거
     *
     * @property domain IDNA ASCII로 정규화된 도메인
     */
    data class Domain(val domain: String) : SpoolTrigger
}
