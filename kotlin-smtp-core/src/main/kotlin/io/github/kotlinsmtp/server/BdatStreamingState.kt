package io.github.kotlinsmtp.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

/**
 * Encapsulates BDAT (CHUNKING) streaming state.
 *
 * - Keeps channel/stream/job for each transaction.
 * - Safely cleans up through clear() on RSET/transaction end/session end.
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
