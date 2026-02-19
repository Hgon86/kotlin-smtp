package io.github.kotlinsmtp.auth

import java.util.Base64

/**
 * Simple authentication service interface
 */
public interface AuthService {
    public val enabled: Boolean
    public val required: Boolean

    /**
     * Verify username/password
     */
    public fun verify(username: String, password: String): Boolean
}

/**
 * SASL PLAIN initial response (Base64) decoding utility
 * Format: authzid\0authcid\0passwd
 */
internal object SaslPlain {
    internal data class Credentials(val authzid: String?, val authcid: String, val password: String)

    fun decode(initialResponseBase64: String): Credentials? = runCatching {
        val decoded = Base64.getDecoder().decode(initialResponseBase64)
        val s = String(decoded, Charsets.UTF_8)
        val parts = s.split('\u0000')
        if (parts.size >= 3) Credentials(parts[0].ifBlank { null }, parts[1], parts[2]) else null
    }.getOrNull()
}
