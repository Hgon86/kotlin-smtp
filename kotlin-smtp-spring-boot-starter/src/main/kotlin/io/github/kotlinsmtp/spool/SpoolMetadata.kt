package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import java.nio.file.Path
import java.time.Instant

/**
 * 스풀 메시지 메타데이터
 */
data class SpoolMetadata(
    val id: String,
    val rawPath: Path,
    val sender: String?,
    var recipients: MutableList<String>,
    val messageId: String,
    val authenticated: Boolean,
    val peerAddress: String? = null,
    val dsnRet: String? = null,
    val dsnEnvid: String? = null,
    var rcptDsn: MutableMap<String, RcptDsn> = mutableMapOf(),
    var attempt: Int = 0,
    var nextAttemptAt: Instant = Instant.now(),
)
