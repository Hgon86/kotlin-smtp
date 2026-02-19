package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.Values
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.TooLongFrameException
import io.netty.util.AttributeKey
import io.netty.util.CharsetUtil

/**
 * SMTP inbound framing decoder
 *
 * Existing LineBasedFrameDecoder/StringDecoder combination cannot handle BDAT (CHUNKING).
 * (BDAT requires reading exactly N bytes, and chunk bytes may include CRLF)
 *
 * This decoder switches internal state between "line mode" and "raw bytes mode".
 * - Default is line mode (CRLF-based), emitting [SmtpInboundFrame.Line].
 * - When BDAT line is detected, it reads the following bytes as "exactly N bytes" and emits [SmtpInboundFrame.Bytes].
 *
 * NOTE: BDAT command line and chunk bytes can arrive in the same packet,
 *       so the approach of downstream setting expectedBytes via attribute can break due to races.
 *       Therefore this decoder directly recognizes BDAT lines and switches mode.
 */
internal class SmtpInboundDecoder(
    private val maxLineLength: Int = Values.MAX_SMTP_LINE_LENGTH,
    private val strictCrlf: Boolean = false,
) : ByteToMessageDecoder() {

    companion object {
        /**
         * While receiving DATA body, "body lines" may look like commands (e.g., "BDAT 123").
         * In that case BDAT auto-detection would break framing, so auto-detect is disabled in DATA mode.
         */
        internal val IN_DATA_MODE: AttributeKey<Boolean> =
            AttributeKey.valueOf("smtp.inDataMode")
    }

    private var pendingRawBytes: Int? = null

    /**
     * DATA body receiving mode tracked at decoder level.
     *
     * To avoid framing breaks even before session reflects `inDataMode=true` (pipelining/same packet),
     * BDAT auto-detect is disabled immediately when DATA line is detected.
     */
    private var dataModeForFraming: Boolean = false

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
                    // Defense: block oversized chunk at decoder stage (prevent memory blow-up)
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

            if (strictCrlf && !hasCr) {
                // RFC 5321 requires CRLF. Keep permissive by default for interoperability.
                input.readerIndex(lfIndex + 1)
                throw IllegalArgumentException("SMTP line must end with CRLF")
            }

            if (lineLength > maxLineLength) {
                // Discard this line and handle as error
                input.readerIndex(lfIndex + 1)
                throw TooLongFrameException("SMTP line too long (max=$maxLineLength bytes)")
            }

            val lineBytes = ByteArray(lineLength)
            input.readBytes(lineBytes)
            if (hasCr) input.skipBytes(1) // '\r'
            input.skipBytes(1) // '\n'

            // Preserve SMTP command/data bytes 1:1 with ISO-8859-1 for 8BITMIME considerations.
            val line = String(lineBytes, CharsetUtil.ISO_8859_1)

            // While receiving DATA body, body lines may start with "BDAT ...", so disable auto-detect.
            // Use decoder-local state as well to guard inputs before session reflects IN_DATA_MODE.
            val inDataMode = dataModeForFraming || (ctx.channel().attr(IN_DATA_MODE).get() == true)
            if (!inDataMode) {
                parseBdatSizeIfAny(line)?.let { size ->
                    if (size > Values.MAX_BDAT_CHUNK_SIZE) {
                        throw TooLongFrameException("BDAT chunk too large (max=${Values.MAX_BDAT_CHUNK_SIZE} bytes)")
                    }
                    pendingRawBytes = size
                }
            }

            // Track DATA mode enter/exit at framing level.
            // - Body may continue right after DATA line (even before 354 response).
            // - After processing body terminator ('.'), next command line must be parsed normally.
            val commandPart = line.trimStart()
            if (!dataModeForFraming && commandPart.equals("DATA", ignoreCase = true)) {
                dataModeForFraming = true
            } else if (dataModeForFraming && line == ".") {
                dataModeForFraming = false
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

    /**
     * Parse BDAT <size> line.
     *
     * - size is a non-negative integer.
     * - Ignores trailing tokens such as LAST.
     */
    private fun parseBdatSizeIfAny(line: String): Int? {
        val trimmed = line.trimStart()
        if (trimmed.length < 4) return null
        if (!trimmed.regionMatches(0, "BDAT", 0, 4, ignoreCase = true)) return null
        if (trimmed.length > 4 && !trimmed[4].isWhitespace()) return null

        var i = 4
        while (i < trimmed.length && trimmed[i].isWhitespace()) i++
        if (i >= trimmed.length) return null

        var value = 0L
        var digits = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (!c.isDigit()) break
            value = value * 10 + (c.code - '0'.code)
            if (value > Int.MAX_VALUE.toLong()) return null
            digits++
            i++
        }
        if (digits == 0) return null
        return value.toInt()
    }
}
