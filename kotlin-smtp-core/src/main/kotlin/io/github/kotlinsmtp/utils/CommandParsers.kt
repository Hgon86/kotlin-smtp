package io.github.kotlinsmtp.utils

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.utils.SmtpStatusCode.RECIPIENT_NOT_RECOGNIZED

/**
 * MAIL FROM 명령 파싱 결과
 * @property address 발신자 이메일 주소 (빈 문자열은 빈 reverse-path를 의미)
 * @property parameters ESMTP 파라미터 맵 (키는 대문자로 정규화)
 */
internal data class MailArguments(
    val address: String,
    val parameters: Map<String, String>,
)

/**
 * RCPT TO 명령 파싱 결과
 * @property address 수신자 이메일 주소
 * @property parameters ESMTP 파라미터 맵 (키는 대문자로 정규화)
 */
internal data class RcptArguments(
    val address: String,
    val parameters: Map<String, String>,
)

/**
 * MAIL FROM 명령 문자열을 파싱합니다.
 * 예: "MAIL FROM:<user@example.com> SIZE=1234 BODY=7BIT"
 * 
 * @param raw 명령 전체 문자열
 * @return 파싱된 MailArguments
 * @throws IllegalArgumentException 파싱 실패 시
 */
internal fun parseMailArguments(raw: String): MailArguments {
    val trimmed = raw.trim()
    
    // "MAIL FROM:" 키워드 검증
    if (!trimmed.uppercase().startsWith("MAIL FROM:")) {
        throw IllegalArgumentException("Missing MAIL FROM keyword")
    }
    
    // 괄호 안의 주소 추출
    val address = AddressUtils.extractFromBrackets(trimmed)
        ?: throw IllegalArgumentException("Missing angle brackets around sender")
    
    // 괄호 뒤의 파라미터 추출
    val params = extractParameters(trimmed)
    
    return MailArguments(address, params)
}

/**
 * RCPT TO 명령 문자열을 파싱합니다.
 * 예: "RCPT TO:<recipient@example.com> NOTIFY=NEVER"
 * 
 * @param raw 명령 전체 문자열
 * @return 파싱된 RcptArguments
 * @throws IllegalArgumentException 파싱 실패 시
 */
internal fun parseRcptArguments(raw: String): RcptArguments {
    val trimmed = raw.trim()
    
    // "RCPT TO:" 키워드 검증
    if (!trimmed.uppercase().startsWith("RCPT TO:")) {
        throw IllegalArgumentException("Missing RCPT TO keyword")
    }
    
    // 괄호 안의 주소 추출
    val address = AddressUtils.extractFromBrackets(trimmed)
        ?: throw IllegalArgumentException("Missing angle brackets around recipient")
    
    // 괄호 뒤의 파라미터 추출
    val params = extractParameters(trimmed)
    
    return RcptArguments(address, params)
}

/**
 * 명령 문자열에서 닫는 괄호(>) 이후의 ESMTP 파라미터를 추출합니다.
 * 파라미터 형식: KEY=VALUE 또는 KEY (값 없음)
 * 
 * DoS 공격 방지를 위한 제한:
 * - 최대 파라미터 개수: 10개
 * - 파라미터 최대 길이: 1024바이트
 * 
 * @param segment 전체 명령 문자열
 * @return 파라미터 맵 (키는 대문자, 값이 없으면 빈 문자열)
 * @throws IllegalArgumentException 제한 초과 시
 */
private fun extractParameters(segment: String): Map<String, String> {
    val closingIndex = segment.lastIndexOf('>')
    if (closingIndex < 0 || closingIndex + 1 >= segment.length) return emptyMap()
    
    val remainder = segment.substring(closingIndex + 1).trim()
    if (remainder.isEmpty()) return emptyMap()
    
    val tokens = remainder.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    
    // 파라미터 개수 제한 (DoS 방지)
    if (tokens.size > 10) {
        throw IllegalArgumentException("Too many parameters (max 10)")
    }
    
    return tokens.associate { token ->
        // 파라미터 길이 제한 (DoS 방지)
        if (token.length > 1024) {
            throw IllegalArgumentException("Parameter too long (max 1024 bytes)")
        }
        
        val equalsIndex = token.indexOf('=')
        if (equalsIndex > 0) {
            // KEY=VALUE 형식
            val key = token.substring(0, equalsIndex).uppercase()
            val value = token.substring(equalsIndex + 1)
            key to value
        } else {
            // KEY만 있는 형식 (값 없음)
            token.uppercase() to ""
        }
    }
}

/**
 * 파라미터 맵에서 허용되지 않는 파라미터가 있는지 검증합니다.
 * 
 * @param disallowed 금지된 파라미터 이름 집합 (대문자)
 * @throws SmtpSendResponse 금지된 파라미터 발견 시 555 오류
 */
internal fun Map<String, String>.ensureNoUnsupportedParams(disallowed: Set<String>) {
    for (key in keys) {
        if (key.uppercase() in disallowed) {
            throw SmtpSendResponse(
                RECIPIENT_NOT_RECOGNIZED.code,
                "$key parameter not supported"
            )
        }
    }
}
