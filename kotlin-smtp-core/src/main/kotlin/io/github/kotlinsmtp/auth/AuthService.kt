package io.github.kotlinsmtp.auth

import java.util.Base64

/**
 * 간단한 인증 서비스 인터페이스
 */
interface AuthService {
    val enabled: Boolean
    val required: Boolean

    /**
     * 사용자명/비밀번호 검증
     */
    fun verify(username: String, password: String): Boolean
}

/**
 * SASL PLAIN 초기 응답(Base64) 디코딩 유틸리티
 * 포맷: authzid\0authcid\0passwd
 */
object SaslPlain {
    data class Credentials(val authzid: String?, val authcid: String, val password: String)

    fun decode(initialResponseBase64: String): Credentials? = runCatching {
        val decoded = Base64.getDecoder().decode(initialResponseBase64)
        val s = String(decoded, Charsets.UTF_8)
        val parts = s.split('\u0000')
        if (parts.size >= 3) Credentials(parts[0].ifBlank { null }, parts[1], parts[2]) else null
    }.getOrNull()
}
