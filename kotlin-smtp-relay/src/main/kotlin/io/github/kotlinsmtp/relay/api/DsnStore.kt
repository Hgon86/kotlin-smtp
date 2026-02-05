package io.github.kotlinsmtp.relay.api

import io.github.kotlinsmtp.model.RcptDsn
import java.nio.file.Path

/**
 * DSN 메시지(RFC822 원문)를 스풀/큐에 등록하는 최소 경계.
 */
public fun interface DsnStore {
    public fun enqueue(
        rawMessagePath: Path,
        envelopeSender: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
        dsnRet: String?,
        dsnEnvid: String?,
        rcptDsn: Map<String, RcptDsn>,
    )
}
