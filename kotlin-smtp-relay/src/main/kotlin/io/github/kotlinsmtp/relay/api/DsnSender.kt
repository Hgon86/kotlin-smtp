package io.github.kotlinsmtp.relay.api

import io.github.kotlinsmtp.model.RcptDsn
import java.nio.file.Path

/**
 * DSN(Delivery Status Notification) 발송을 위한 최소 경계.
 */
public interface DsnSender {
    /**
     * @param sender 원본 메시지의 envelope sender(MAIL FROM). null/blank/<>이면 DSN을 생략한다.
     * @param failedRecipients 실패한 수신자와 간단한 사유 목록
     * @param originalMessageId 원본 메시지 식별자
     * @param originalMessagePath RET 정책에 따라 첨부할 원문 경로(선택)
     */
    public fun sendPermanentFailure(
        sender: String?,
        failedRecipients: List<Pair<String, String>>,
        originalMessageId: String,
        originalMessagePath: Path? = null,
        dsnEnvid: String? = null,
        dsnRet: String? = null,
        rcptDsn: Map<String, RcptDsn> = emptyMap(),
    )
}
