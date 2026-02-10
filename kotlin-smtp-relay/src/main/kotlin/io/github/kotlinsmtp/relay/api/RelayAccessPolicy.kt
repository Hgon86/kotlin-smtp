package io.github.kotlinsmtp.relay.api

/**
 * 릴레이 허용/거부 정책(오픈 릴레이 방지의 1차 경계).
 */
public fun interface RelayAccessPolicy {
    public fun evaluate(context: RelayAccessContext): RelayAccessDecision
}

/**
 * @property envelopeSender MAIL FROM(reverse-path)
 * @property recipient RCPT TO
 * @property authenticated 세션 인증 여부
 */
public data class RelayAccessContext(
    public val envelopeSender: String?,
    public val recipient: String,
    public val authenticated: Boolean,
)

public sealed interface RelayAccessDecision {
    public data object Allowed : RelayAccessDecision

    /**
     * @property reason 표준화된 거부 사유
     * @property message 운영/로그를 위한 짧은 설명(민감정보 최소화 권장)
     */
    public data class Denied(
        public val reason: RelayDeniedReason,
        public val message: String? = null,
    ) : RelayAccessDecision
}

public enum class RelayDeniedReason {
    AUTH_REQUIRED,
    SENDER_DOMAIN_NOT_ALLOWED,
    OTHER_POLICY,
}
