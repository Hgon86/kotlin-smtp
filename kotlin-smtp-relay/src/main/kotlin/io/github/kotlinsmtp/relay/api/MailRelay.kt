package io.github.kotlinsmtp.relay.api

import java.io.InputStream

/**
 * 외부 도메인으로 RFC822 원문을 릴레이 전송한다.
 */
public interface MailRelay {
    public suspend fun relay(request: RelayRequest): RelayResult
}

/**
 * 릴레이 호출 최소 입력(원문 + 엔벌로프 + 메타).
 *
 * @property messageId 내부 추적용 식별자(로그/DSN 등에 사용)
 * @property envelopeSender MAIL FROM(reverse-path). bounce(<>)를 표현하려면 null/blank를 사용한다.
 * @property recipient RCPT TO(단일 수신자). fan-out은 호출자가 담당한다.
 * @property authenticated 세션 인증 여부(정책/로그에 활용)
 * @property rfc822 RFC822 원문 소스
 */
public data class RelayRequest(
    public val messageId: String,
    public val envelopeSender: String?,
    public val recipient: String,
    public val authenticated: Boolean,
    public val rfc822: Rfc822Source,
)

/**
 * RFC822 원문 제공자.
 */
public fun interface Rfc822Source {
    public fun openStream(): InputStream
}

/**
 * 릴레이 성공 결과(최소한만).
 */
public data class RelayResult(
    public val remoteHost: String? = null,
    public val remotePort: Int? = null,
    public val serverGreeting: String? = null,
)
