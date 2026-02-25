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
    var processorJob: Job? = null
        private set
    var processorResult: CompletableDeferred<Result<Unit>>? = null
        private set

    val isActive: Boolean
        get() = dataChannel != null

    fun start(
        dataChannel: Channel<ByteArray>,
        stream: CoroutineInputStream,
        processorJob: Job,
        processorResult: CompletableDeferred<Result<Unit>>,
    ) {
        this.dataChannel = dataChannel
        this.stream = stream
        this.processorJob = processorJob
        this.processorResult = processorResult
    }

    suspend fun clear() {
        processorJob?.cancel()
        runCatching { processorJob?.join() }

        runCatching { stream?.close() }
        runCatching { dataChannel?.close() }

        dataChannel = null
        stream = null
        processorJob = null
        processorResult = null
    }
}
