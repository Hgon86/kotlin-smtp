package com.crinity.kotlinsmtp.server

import com.crinity.kotlinsmtp.utils.Values
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.TooLongFrameException
import io.netty.util.AttributeKey
import io.netty.util.CharsetUtil

/**
 * SMTP 입력 프레이밍 디코더
 *
 * 기존 LineBasedFrameDecoder/StringDecoder 조합은 BDAT(CHUNKING) 처리를 할 수 없습니다.
 * (BDAT는 '정확히 N바이트'를 읽어야 하며, 청크 바이트 안에는 CRLF가 포함될 수 있음)
 *
 * 이 디코더는 "라인 모드"와 "raw bytes 모드"를 내부 상태로 전환합니다.
 * - 기본은 라인 모드(CRLF 기준)로 [SmtpInboundFrame.Line]을 출력합니다.
 * - BDAT 라인을 감지하면, 다음에 오는 바이트를 "정확히 N바이트"로 읽어 [SmtpInboundFrame.Bytes]를 출력합니다.
 *
 * NOTE: BDAT는 커맨드 라인과 청크 바이트가 같은 패킷에 함께 올 수 있어,
 *       '다운스트림이 attribute로 expectedBytes를 설정'하는 방식은 레이스로 깨질 수 있습니다.
 *       그래서 디코더가 BDAT 라인을 직접 인지해 모드를 전환합니다.
 */
class SmtpInboundDecoder(
    private val maxLineLength: Int = Values.MAX_SMTP_LINE_LENGTH,
) : ByteToMessageDecoder() {

    companion object {
        /**
         * DATA 본문 수신 중에는 "본문 라인"이 커맨드처럼 보일 수 있습니다(예: "BDAT 123").
         * 이 경우 BDAT 자동 감지를 하면 프레이밍이 깨지므로, DATA 모드에서는 auto-detect를 비활성화합니다.
         */
        internal val IN_DATA_MODE: AttributeKey<Boolean> =
            AttributeKey.valueOf("smtp.inDataMode")
    }

    private var pendingRawBytes: Int? = null

    override fun decode(ctx: io.netty.channel.ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        while (true) {
            val expectedBytes = pendingRawBytes
            if (expectedBytes != null) {
                if (expectedBytes < 0) {
                    pendingRawBytes = null
                    throw IllegalArgumentException("Invalid pendingRawBytes: $expectedBytes")
                }
                if (expectedBytes == 0) {
                    pendingRawBytes = null
                    out.add(SmtpInboundFrame.Bytes(ByteArray(0)))
                    continue
                }
                if (expectedBytes > Values.MAX_BDAT_CHUNK_SIZE) {
                    // 방어: 너무 큰 청크는 디코더 단계에서 차단(메모리 폭주 방지)
                    pendingRawBytes = null
                    throw TooLongFrameException("BDAT chunk too large (max=${Values.MAX_BDAT_CHUNK_SIZE} bytes)")
                }
                if (input.readableBytes() < expectedBytes) return

                val bytes = ByteArray(expectedBytes)
                input.readBytes(bytes)
                pendingRawBytes = null
                out.add(SmtpInboundFrame.Bytes(bytes))
                continue
            }

            val lfIndex = findLf(input)
            if (lfIndex < 0) {
                if (input.readableBytes() > maxLineLength) {
                    throw TooLongFrameException("SMTP line too long (max=$maxLineLength bytes)")
                }
                return
            }

            val readerIndex = input.readerIndex()
            val hasCr = lfIndex > readerIndex && input.getByte(lfIndex - 1).toInt() == '\r'.code
            val lineLength = if (hasCr) lfIndex - readerIndex - 1 else lfIndex - readerIndex

            if (lineLength > maxLineLength) {
                // 해당 라인을 버리고 에러 처리
                input.readerIndex(lfIndex + 1)
                throw TooLongFrameException("SMTP line too long (max=$maxLineLength bytes)")
            }

            val lineBytes = ByteArray(lineLength)
            input.readBytes(lineBytes)
            if (hasCr) input.skipBytes(1) // '\r'
            input.skipBytes(1) // '\n'

            // SMTP 커맨드/데이터는 8BITMIME를 고려해 ISO-8859-1로 1:1 보존합니다.
            val line = String(lineBytes, CharsetUtil.ISO_8859_1)

            // BDAT 감지 시, 다음 bytes를 raw 모드로 프레이밍하도록 예약합니다.
            // - 유효한 BDAT 라인이면, 청크 바이트가 같은 패킷에 있어도 안전하게 Bytes 프레임으로 분리됩니다.
            // - 단, DATA 본문 수신 중에는 본문 라인이 "BDAT ..."로 시작할 수 있으므로 auto-detect를 끕니다.
            val inDataMode = ctx.channel().attr(IN_DATA_MODE).get() == true
            if (!inDataMode) {
                parseBdatSizeIfAny(line)?.let { size ->
                    if (size > Values.MAX_BDAT_CHUNK_SIZE) {
                        throw TooLongFrameException("BDAT chunk too large (max=${Values.MAX_BDAT_CHUNK_SIZE} bytes)")
                    }
                    pendingRawBytes = size
                }
            }
            out.add(SmtpInboundFrame.Line(line))
        }
    }

    private fun findLf(input: ByteBuf): Int {
        val start = input.readerIndex()
        val end = input.writerIndex()
        for (i in start until end) {
            if (input.getByte(i).toInt() == '\n'.code) return i
        }
        return -1
    }

    private fun parseBdatSizeIfAny(line: String): Int? {
        val trimmed = line.trimStart()
        if (trimmed.length < 4) return null
        if (!trimmed.regionMatches(0, "BDAT", 0, 4, ignoreCase = true)) return null
        if (trimmed.length > 4 && !trimmed[4].isWhitespace()) return null

        val parts = trimmed.split(Values.whitespaceRegex).filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val sizeLong = parts[1].toLongOrNull() ?: return null
        if (sizeLong < 0) return null
        if (sizeLong > Int.MAX_VALUE.toLong()) return null
        return sizeLong.toInt()
    }
}

