package io.github.kotlinsmtp.relay.api

import io.github.kotlinsmtp.model.RcptDsn
import java.nio.file.Path

/**
 * Minimal boundary for enqueuing DSN messages (RFC822 raw) to spool/queue.
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
