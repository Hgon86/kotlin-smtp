package io.github.kotlinsmtp.model

/**
 * RFC 3461(DSN) 관련 RCPT TO 확장 파라미터 최소 모델
 *
 * NOTE:
 * - 현재는 "실사용에 필요한 최소"로 저장만 하고,
 *   DSN 발송 시 NOTIFY=NEVER/FAILURE 여부를 반영하는 수준으로 사용합니다.
 * - ORCPT는 향후 RFC 3464 형식의 DSN 생성 시 Original-Recipient에 반영하는 것을 TODO로 둡니다.
 */
data class RcptDsn(
    val notify: String? = null,
    val orcpt: String? = null,
)
