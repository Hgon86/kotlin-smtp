package io.github.kotlinsmtp.relay.api

/**
 * outbound 릴레이 실패 분류.
 *
 * 구현체는 실패를 transient/permanent로 분류하여 스풀러/재시도 정책이 판단할 수 있도록 한다.
 */
public sealed class RelayException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    public abstract val isTransient: Boolean

    /** RFC 3463 Enhanced Status Code(예: 4.4.1, 5.7.1). */
    public open val enhancedStatusCode: String? = null

    /** 원격 서버 응답(가능한 경우). */
    public open val remoteReply: String? = null
}

public class RelayTransientException(
    message: String,
    cause: Throwable? = null,
) : RelayException(message, cause) {
    override val isTransient: Boolean = true
}

public class RelayPermanentException(
    message: String,
    cause: Throwable? = null,
) : RelayException(message, cause) {
    override val isTransient: Boolean = false
}
