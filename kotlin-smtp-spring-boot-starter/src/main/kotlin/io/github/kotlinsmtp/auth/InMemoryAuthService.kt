package io.github.kotlinsmtp.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

private val log = KotlinLogging.logger {}

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
