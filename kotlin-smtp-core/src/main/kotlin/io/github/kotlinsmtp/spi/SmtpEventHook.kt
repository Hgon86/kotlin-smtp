package io.github.kotlinsmtp.spi

import io.github.kotlinsmtp.model.RcptDsn

/**
 * Minimal event hook (SPI) exposed by the core engine.
 *
 * - Implementations such as storage (S3/DB), metadata logging, Kafka publishing are not included in core,
 *   and host can connect invocation points through this hook.
 * - Hook exceptions do not fail server operation by default policy (non-fatal); core only logs them.
 * - Hook implementations should return quickly when possible; long tasks are recommended to be delegated asynchronously.
 */
public interface SmtpEventHook {

    /** Called right after session starts and 220 greeting is sent. */
    public suspend fun onSessionStarted(event: SmtpSessionStartedEvent): Unit = Unit

    /** Called right before session ends. */
    public suspend fun onSessionEnded(event: SmtpSessionEndedEvent): Unit = Unit

    /** Called right after DATA/BDAT transaction succeeds (final 250). */
    public suspend fun onMessageAccepted(event: SmtpMessageAcceptedEvent): Unit = Unit

    /** Called right after DATA/BDAT transaction fails (4xx/5xx). */
    public suspend fun onMessageRejected(event: SmtpMessageRejectedEvent): Unit = Unit
}

/** Common context containing session identity/environment info. */
public data class SmtpSessionContext(
    public val sessionId: String,
    public val peerAddress: String?,
    public val serverHostname: String?,
    public val helo: String?,
    public val tlsActive: Boolean,
    public val authenticated: Boolean,
)

/** Message envelope information. */
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
