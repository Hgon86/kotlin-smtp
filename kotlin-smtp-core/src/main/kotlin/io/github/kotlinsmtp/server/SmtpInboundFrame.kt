package io.github.kotlinsmtp.server

/**
 * Framed result of Netty inbound byte stream into SMTP-level consumable forms.
 *
 * - [Line]: "text line" ending with CRLF (SMTP command line, DATA line)
 * - [Bytes]: chunk requiring exact byte-length reads, such as BDAT
 */
internal sealed interface SmtpInboundFrame {
    data class Line(val text: String) : SmtpInboundFrame
    data class Bytes(val bytes: ByteArray) : SmtpInboundFrame
}
