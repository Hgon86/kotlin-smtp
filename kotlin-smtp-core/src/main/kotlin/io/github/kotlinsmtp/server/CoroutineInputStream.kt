package io.github.kotlinsmtp.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/**
 * InputStream implementation based on coroutine channel
 */
internal class CoroutineInputStream(private val channel: Channel<ByteArray>) : InputStream() {
    private var buffer: ByteArray = ByteArray(0)
    private var position: Int = 0
    private var closed = false

    override fun read(): Int {
        if (closed) return -1

        // Fetch new data if current buffer is empty
        if (position >= buffer.size && !fillBuffer()) return -1

        // Return byte at current position (convert to 0-255 range)
        return buffer[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (b.isEmpty()) return 0
        if (closed) return -1
        if (len == 0) return 0
        if (off < 0 || len < 0 || off + len > b.size) {
            throw IndexOutOfBoundsException("Offset: $off, Length: $len, Array size: ${b.size}")
        }

        // InputStream contract: it may return only as much as currently available.
        // Blocking until len is fully filled can unnecessarily delay caller (handler),
        // so return only what can be read from current buffer.
        if (position >= buffer.size && !fillBuffer()) return -1

        val available = minOf(buffer.size - position, len)
        System.arraycopy(buffer, position, b, off, available)
        position += available
        return available
    }

    private fun fillBuffer(): Boolean {
        val result = runBlocking { channel.receiveCatching() }
        val nextChunk = result.getOrNull()
        if (nextChunk != null) {
            buffer = nextChunk
            position = 0
            return true
        }

        val cause = result.exceptionOrNull()
        if (cause != null) {
            throw IOException("CoroutineInputStream closed with error", cause)
        }
        return false
    }

    override fun available(): Int = if (closed) 0 else buffer.size - position

    /** Close stream and underlying channel (idempotent). */
    override fun close() {
        if (!closed) {
            closed = true
            channel.close()
        }
    }
}
