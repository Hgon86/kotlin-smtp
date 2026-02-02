package com.crinity.kotlinsmtp.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Base64

private val log = KotlinLogging.logger {}

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
 * 인메모리 사용자 저장소 기반 인증 서비스(BCrypt 지원)
 */
class InMemoryAuthService(
    override val enabled: Boolean,
    override val required: Boolean,
    private val users: Map<String, String>, // 값은 해시 또는 평문(자동 감지)
    private val passwordEncoder: BCryptPasswordEncoder = BCryptPasswordEncoder(),
) : AuthService {
    override fun verify(username: String, password: String): Boolean {
        val stored = users[username]
        val ok = when {
            stored == null -> false
            stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$") ->
                runCatching { passwordEncoder.matches(password, stored) }.getOrDefault(false)
            else -> stored == password
        }
        if (!ok) log.warn { "Auth failed for user '$username'" }
        return ok
    }
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
