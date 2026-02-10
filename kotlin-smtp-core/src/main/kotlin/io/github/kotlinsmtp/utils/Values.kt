package io.github.kotlinsmtp.utils

internal object Values {
    val whitespaceRegex = Regex("\\s+")
    const val MAX_MESSAGE_SIZE = 52_428_800 // 50MB in bytes
    const val MAX_RECIPIENTS = 100 // 세션당 최대 RCPT 수
    const val MAX_COMMAND_LINE_LENGTH = 2048 // RFC 5321 권장 512, ESMTP 확장 허용

    /**
     * SMTP 라인(커맨드 라인/ DATA 라인) 최대 길이
     *
     * - Netty inbound에서 CRLF 기준으로 프레이밍할 때의 상한입니다.
     * - DATA 본문 라인은 RFC 5322 권장보다 긴 경우도 있어, 운영상 적절한 상한을 둡니다.
     */
    const val MAX_SMTP_LINE_LENGTH = 8192

    /**
     * BDAT(CHUNKING) 청크 최대 크기
     *
     * - BDAT는 '정확히 N바이트'를 메모리 버퍼로 읽게 되므로 청크 상한이 필요합니다.
     * - 총 메시지 크기 제한은 [MAX_MESSAGE_SIZE]로 별도 적용합니다.
     */
    const val MAX_BDAT_CHUNK_SIZE = 8 * 1024 * 1024 // 8MB

    /**
     * BDAT 바이트 프레임 인플라이트(대기 중) 총량 제한 (bytes)
     *
     * - 현재 구현은 BDAT 청크를 ByteArray로 버퍼링한 뒤 코루틴 채널로 전달합니다.
     * - 프레임 개수(capacity) 제한만으로는 큰 청크에서 메모리 폭주를 막기 어려우므로,
     *   "대기 중인 총 바이트" 상한을 별도로 둡니다.
     * - 기본값: [MAX_BDAT_CHUNK_SIZE] * 2
     */
    const val MAX_INFLIGHT_BDAT_BYTES: Int = MAX_BDAT_CHUNK_SIZE * 2
}
