package io.github.kotlinsmtp.server

import io.github.kotlinsmtp.utils.SmtpStatusCode
import io.github.kotlinsmtp.utils.Values
import java.net.InetSocketAddress

/**
 * SMTP 입력 로그에서 민감 정보를 마스킹합니다.
 */
internal class SmtpSessionLogSanitizer {
    /**
     * 로깅 가능한 안전한 문자열로 변환합니다.
     *
     * @param line 원본 입력 라인
     * @param inDataMode DATA 본문 수신 여부
     * @param dataModeFramingHint DATA 모드 전환 힌트 여부
     * @return 마스킹된 로그 문자열
     */
    fun sanitize(line: String, inDataMode: Boolean, dataModeFramingHint: Boolean): String {
        if (inDataMode || dataModeFramingHint) {
            return if (line == ".") "<DATA:END>" else "<DATA:${line.length} chars>"
        }

        val trimmed = line.trimStart()
        if (!trimmed.regionMatches(0, "AUTH", 0, 4, ignoreCase = true)) return line

        val parts = trimmed.split(Values.whitespaceRegex, limit = 3)
        return when {
            parts.size >= 2 && parts[1].equals("PLAIN", ignoreCase = true) -> "AUTH PLAIN ***"
            parts.size >= 2 -> "AUTH ${parts[1]} ***"
            else -> "AUTH ***"
        }
    }
}

/**
 * SMTP 응답 포맷을 표준 형태로 생성합니다.
 */
internal class SmtpResponseFormatter {
    /**
     * 단일 응답 라인을 생성합니다.
     *
     * @param code SMTP 상태 코드
     * @param message 응답 메시지
     * @return RFC 형식 응답 라인
     */
    fun formatLine(code: Int, message: String?): String {
        if (message != null && enhancedStatusRegex.containsMatchIn(message.trimStart())) {
            return "$code $message"
        }

        val statusCode = SmtpStatusCode.fromCode(code)
        return when {
            statusCode != null -> statusCode.formatResponse(message)
            message != null -> "$code $message"
            else -> code.toString()
        }
    }

    /**
     * 멀티라인 응답 라인을 생성합니다.
     *
     * @param code SMTP 상태 코드
     * @param lines 응답 라인 목록
     * @return RFC 형식 멀티라인 응답
     */
    fun formatMultiline(code: Int, lines: List<String>): List<String> {
        val statusCode = SmtpStatusCode.fromCode(code)
        val enhancedPrefix = statusCode?.enhancedCode?.let { "$it " } ?: ""

        return lines.mapIndexed { index, line ->
            if (index != lines.lastIndex) "$code-$enhancedPrefix$line"
            else "$code $enhancedPrefix$line"
        }
    }
}

/**
 * 원격 피어 주소를 로깅/메타데이터용 문자열로 변환합니다.
 */
internal object SmtpPeerAddressResolver {
    /**
     * 주소를 표준 문자열로 변환합니다.
     *
     * @param address 원격 주소 객체
     * @return `host:port` 또는 `[ipv6]:port` 형식 문자열
     */
    fun resolve(address: Any?): String? {
        val inet = (address as? InetSocketAddress) ?: return address?.toString()
        val ipOrHost = inet.address?.hostAddress ?: inet.hostString
        return if (ipOrHost.contains(':')) "[$ipOrHost]:${inet.port}" else "$ipOrHost:${inet.port}"
    }
}

// "5.7.1 ..." 같은 Enhanced Status Code 형태를 감지합니다.
private val enhancedStatusRegex = Regex("^\\d\\.\\d\\.\\d\\b")
