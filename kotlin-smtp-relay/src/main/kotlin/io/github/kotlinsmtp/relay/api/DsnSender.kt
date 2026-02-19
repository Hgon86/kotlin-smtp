package io.github.kotlinsmtp.relay.api

import io.github.kotlinsmtp.model.RcptDsn
import java.nio.file.Path

/**
 * Minimal boundary for sending DSN (Delivery Status Notification).
 */
public interface DsnSender {
    /**
     * @param sender Envelope sender (MAIL FROM) of original message. DSN is omitted for null/blank/<>.
     * @param failedRecipients List of failed recipients with simple reasons
     * @param originalMessageId Identifier of original message
     * @param originalMessagePath Optional path of original message to attach based on RET policy
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
