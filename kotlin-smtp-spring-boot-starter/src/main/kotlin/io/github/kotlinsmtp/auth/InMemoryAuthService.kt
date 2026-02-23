package io.github.kotlinsmtp.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

private val log = KotlinLogging.logger {}

internal fun String.isBcryptHash(): Boolean =
    startsWith("\$2a\$") || startsWith("\$2b\$") || startsWith("\$2y\$")

/**
 * Authentication service backed by in-memory user store (BCrypt supported)
 */
class InMemoryAuthService(
    override val enabled: Boolean,
    override val required: Boolean,
    private val users: Map<String, String>, // Values may be hash or plaintext (auto-detected)
    private val allowPlaintextPasswords: Boolean = true,
    private val passwordEncoder: BCryptPasswordEncoder = BCryptPasswordEncoder(),
) : AuthService {
    init {
        val plaintextCount = users.values.count { !it.isBcryptHash() }
        if (plaintextCount > 0 && allowPlaintextPasswords) {
            log.warn {
                "InMemoryAuthService is using $plaintextCount plaintext credential entries. " +
                    "Use BCrypt hashes for production safety or set smtp.auth.allowPlaintextPasswords=false."
            }
        }
    }

    override fun verify(username: String, password: String): Boolean {
        val stored = users[username]
        val ok = when {
            stored == null -> false
            stored.isBcryptHash() ->
                runCatching { passwordEncoder.matches(password, stored) }.getOrDefault(false)
            allowPlaintextPasswords -> stored == password
            else -> false
        }
        if (!ok) log.warn { "Auth failed for user='${maskIdentity(username)}'" }
        return ok
    }

}
