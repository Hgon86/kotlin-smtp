package io.github.kotlinsmtp.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
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

        var bytesRead = 0
        var currentOffset = off

        while (bytesRead < len) {
            // 현재 버퍼에서 읽을 수 있는 바이트가 없으면 새 데이터 가져오기
            if (position >= buffer.size && !fillBuffer()) return if (bytesRead > 0) bytesRead else -1

            // 현재 버퍼에서 읽을 수 있는 바이트 수 계산
            val available = minOf(buffer.size - position, len - bytesRead)

            // 데이터 복사
            System.arraycopy(buffer, position, b, currentOffset, available)

            position += available
            currentOffset += available
            bytesRead += available
        }

        return bytesRead
    }

    private fun fillBuffer(): Boolean {
        return try {
            val nextChunk = runBlocking { channel.receiveCatching().getOrNull() } ?: return false

            buffer = nextChunk
            position = 0
            true
        } catch (e: Exception) {
            // 채널에서 데이터를 받는 중 예외 발생
            false
        }
    }

    override fun available(): Int = if (closed) 0 else buffer.size - position

    override fun close() {
        if (!closed) {
            closed = true
            runBlocking { channel.close() }
        }
    }
}
