package com.crinity.kotlinsmtp.utils

import jakarta.mail.internet.InternetAddress

object AddressUtils {
    /**
     * 문자열에서 괄호로 둘러싸인 내용 추출
     */
    fun extractFromBrackets(string: String, openingBracket: String = "<", closingBracket: String = ">"): String? =
        string.indexOf(openingBracket).takeIf { it >= 0 }?.let { fromIndex ->
            string.lastIndexOf(closingBracket).takeIf { it > fromIndex }?.let { toIndex ->
                string.slice(fromIndex + 1 until toIndex)
            }
        }

    /**
     * 이메일 주소 검증
     */
    fun validateAddress(address: String): Boolean = runCatching {
        InternetAddress(address).apply { validate() }
        true
    }.getOrDefault(false)

    /**
     * 호스트 부분 검증
     */
    fun validateHost(host: String): Boolean {
        if (!host.startsWith("@")) return false

        return runCatching {
            val domain = host.substring(1) // @ 제거
            InternetAddress("test@$domain").apply { validate() }
            true
        }.getOrDefault(false)
    }

    /**
     * 이메일에서 도메인 부분 추출
     */
    fun extractDomain(email: String): String? =
        email.substringAfterLast('@', "").takeIf { it.isNotEmpty() }
}

/**
 * 문자열이 유효한 이메일 주소인지 확인하는 확장 함수
 */
fun String.isValidEmailAddress(): Boolean = AddressUtils.validateAddress(this)

/**
 * 문자열이 유효한 이메일 호스트인지 확인하는 확장 함수
 */
fun String.isValidEmailHost(): Boolean = AddressUtils.validateHost(this)
