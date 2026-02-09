package io.github.kotlinsmtp.metrics

/**
 * 스풀 처리 경로의 운영 메트릭 기록 경계입니다.
 *
 * @property NOOP 메트릭 비활성 시 사용하는 기본 구현
 */
interface SpoolMetrics {
    /**
     * 초기 대기 메시지 수를 동기화합니다.
     *
     * @param count 현재 스풀 디렉터리의 대기 메시지 수
     */
    fun initializePending(count: Long)

    /** 스풀 큐에 메시지가 1건 적재되었음을 기록합니다. */
    fun onQueued()

    /** 스풀 큐에서 메시지가 정상 완료로 제거되었음을 기록합니다. */
    fun onCompleted()

    /** 스풀 큐에서 메시지가 재시도 한도 초과 등으로 제거되었음을 기록합니다. */
    fun onDropped()

    /**
     * 전달 시도 결과를 기록합니다.
     *
     * @param deliveredCount 성공 수신자 수
     * @param transientFailureCount 일시 실패 수신자 수
     * @param permanentFailureCount 영구 실패 수신자 수
     */
    fun onDeliveryResults(deliveredCount: Int, transientFailureCount: Int, permanentFailureCount: Int)

    /** 재시도 스케줄링이 발생했음을 기록합니다. */
    fun onRetryScheduled()

    companion object {
        /**
         * 메트릭 미구성 환경에서 사용하는 no-op 구현입니다.
         *
         * @return 아무 동작도 하지 않는 구현
         */
        val NOOP: SpoolMetrics = object : SpoolMetrics {
            override fun initializePending(count: Long): Unit = Unit
            override fun onQueued(): Unit = Unit
            override fun onCompleted(): Unit = Unit
            override fun onDropped(): Unit = Unit
            override fun onDeliveryResults(deliveredCount: Int, transientFailureCount: Int, permanentFailureCount: Int): Unit = Unit
            override fun onRetryScheduled(): Unit = Unit
        }
    }
}
