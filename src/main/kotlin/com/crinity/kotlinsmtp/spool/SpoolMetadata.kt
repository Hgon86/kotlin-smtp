package com.crinity.kotlinsmtp.spool

import com.crinity.kotlinsmtp.model.RcptDsn
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
    val dsnRet: String? = null,
    val dsnEnvid: String? = null,
    var rcptDsn: MutableMap<String, RcptDsn> = mutableMapOf(),
    var attempt: Int = 0,
    var nextAttemptAt: Instant = Instant.now(),
)
