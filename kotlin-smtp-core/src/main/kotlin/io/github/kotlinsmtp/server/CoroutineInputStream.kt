package io.github.kotlinsmtp.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/**
 * 코루틴 채널을 기반으로 한 InputStream 구현
 */
internal class CoroutineInputStream(private val channel: Channel<ByteArray>) : InputStream() {
    private var buffer: ByteArray = ByteArray(0)
    private var position: Int = 0
    private var closed = false

    override fun read(): Int {
        if (closed) return -1

        // 현재 버퍼가 비어있으면 새 데이터 가져오기
        if (position >= buffer.size && !fillBuffer()) return -1

        // 현재 위치의 바이트 반환 (0-255 범위로 변환)
        return buffer[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (b.isEmpty()) return 0
        if (closed) return -1
        if (len == 0) return 0
        if (off < 0 || len < 0 || off + len > b.size) {
            throw IndexOutOfBoundsException("오프셋: $off, 길이: $len, 배열 크기: ${b.size}")
        }

        // InputStream 계약: 가능한 만큼만 반환할 수 있습니다.
        // len만큼 채울 때까지 블로킹하면 호출 측(핸들러)이 불필요하게 지연될 수 있으므로,
        // 현재 버퍼에서 읽을 수 있는 만큼만 반환합니다.
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

    /** 스트림과 기반 채널을 종료합니다(멱등). */
    override fun close() {
        if (!closed) {
            closed = true
            channel.close()
        }
    }
}
