package io.github.kotlinsmtp.server

/**
 * Netty inbound 바이트 스트림을 SMTP 레벨에서 소비하기 쉬운 형태로 프레이밍한 결과입니다.
 *
 * - [Line]: CRLF 로 끝나는 "텍스트 라인" (SMTP 커맨드 라인, DATA 라인)
 * - [Bytes]: BDAT 등 "정확한 바이트 길이"로 읽어야 하는 청크
 */
sealed interface SmtpInboundFrame {
    data class Line(val text: String) : SmtpInboundFrame
    data class Bytes(val bytes: ByteArray) : SmtpInboundFrame
}
