package io.github.kotlinsmtp.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

private val log = KotlinLogging.logger {}

/**
 * Authentication service backed by in-memory user store (BCrypt supported)
 */
class InMemoryAuthService(
    override val enabled: Boolean,
    override val required: Boolean,
    private val users: Map<String, String>, // Values may be hash or plaintext (auto-detected)
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
