package io.github.kotlinsmtp.spi

import io.github.kotlinsmtp.model.RcptDsn

/**
 * 코어 엔진이 노출하는 최소 이벤트 훅(SPI)입니다.
 *
 * - 저장(S3/DB), 메타데이터 기록, Kafka 발행 같은 구현은 코어에 포함하지 않고,
 *   호스트가 이 훅을 통해 호출 시점을 연결할 수 있게 합니다.
 * - 훅 예외는 기본 정책상 서버 동작을 실패시키지 않으며(Non-fatal), 코어에서 로깅만 수행합니다.
 */
public interface SmtpEventHook {

    /** 세션이 시작되고, 220 greeting을 보낸 직후 호출됩니다. */
    public suspend fun onSessionStarted(event: SmtpSessionStartedEvent): Unit = Unit

    /** 세션이 종료되기 직전에 호출됩니다. */
    public suspend fun onSessionEnded(event: SmtpSessionEndedEvent): Unit = Unit

    /** DATA/BDAT 트랜잭션이 성공(최종 250) 처리된 직후 호출됩니다. */
    public suspend fun onMessageAccepted(event: SmtpMessageAcceptedEvent): Unit = Unit

    /** DATA/BDAT 트랜잭션이 실패(4xx/5xx) 처리된 직후 호출됩니다. */
    public suspend fun onMessageRejected(event: SmtpMessageRejectedEvent): Unit = Unit
}

/** 세션 식별/환경 정보를 담는 공통 컨텍스트입니다. */
public data class SmtpSessionContext(
    public val sessionId: String,
    public val peerAddress: String?,
    public val serverHostname: String?,
    public val helo: String?,
    public val tlsActive: Boolean,
    public val authenticated: Boolean,
)

/** 메시지 엔벌로프 정보입니다. */
public data class SmtpMessageEnvelope(
    public val mailFrom: String,
    public val rcptTo: List<String>,
    public val dsnEnvid: String?,
    public val dsnRet: String?,
    public val rcptDsn: Map<String, RcptDsn>,
)

public enum class SmtpMessageTransferMode {
    DATA,
    BDAT,
}

public enum class SmtpMessageStage {
    RECEIVING,
    PROCESSING,
}

public enum class SmtpSessionEndReason {
    CLIENT_QUIT,
    IDLE_TIMEOUT,
    PROTOCOL_ERROR,
    UNKNOWN,
}

public data class SmtpSessionStartedEvent(
    public val context: SmtpSessionContext,
)

public data class SmtpSessionEndedEvent(
    public val context: SmtpSessionContext,
    public val reason: SmtpSessionEndReason,
)

public data class SmtpMessageAcceptedEvent(
    public val context: SmtpSessionContext,
    public val envelope: SmtpMessageEnvelope,
    public val transferMode: SmtpMessageTransferMode,
    public val sizeBytes: Long,
)

public data class SmtpMessageRejectedEvent(
    public val context: SmtpSessionContext,
    public val envelope: SmtpMessageEnvelope,
    public val transferMode: SmtpMessageTransferMode,
    public val stage: SmtpMessageStage,
    public val responseCode: Int,
    public val responseMessage: String,
)
