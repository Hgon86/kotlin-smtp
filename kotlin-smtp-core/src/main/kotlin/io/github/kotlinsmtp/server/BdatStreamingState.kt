package io.github.kotlinsmtp.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

/**
 * BDAT(CHUNKING) 스트리밍 상태를 캡슐화합니다.
 *
 * - 트랜잭션 단위로 채널/스트림/잡을 유지합니다.
 * - RSET/트랜잭션 종료/세션 종료 시 clear()로 안전하게 정리합니다.
 */
internal class BdatStreamingState {
    var dataChannel: Channel<ByteArray>? = null
        private set
    var stream: CoroutineInputStream? = null
        private set
    var handlerJob: Job? = null
        private set
    var handlerResult: CompletableDeferred<Result<Unit>>? = null
        private set

    val isActive: Boolean
        get() = dataChannel != null

    fun start(
        dataChannel: Channel<ByteArray>,
        stream: CoroutineInputStream,
        handlerJob: Job,
        handlerResult: CompletableDeferred<Result<Unit>>,
    ) {
        this.dataChannel = dataChannel
        this.stream = stream
        this.handlerJob = handlerJob
        this.handlerResult = handlerResult
    }

    suspend fun clear() {
        handlerJob?.cancel()
        runCatching { handlerJob?.join() }

        runCatching { stream?.close() }
        runCatching { dataChannel?.close() }

        dataChannel = null
        stream = null
        handlerJob = null
        handlerResult = null
    }
}
