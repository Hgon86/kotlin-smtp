package io.github.kotlinsmtp.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class InMemoryAuthServiceSecurityTest {

    private val encoder = BCryptPasswordEncoder()

    @Test
    fun `bcrypt credential verifies when plaintext is disallowed`() {
        val hash = encoder.encode("secret")
        val service = InMemoryAuthService(
            enabled = true,
            required = false,
            users = mapOf("user" to hash),
            allowPlaintextPasswords = false,
        )

        assertTrue(service.verify("user", "secret"))
        assertFalse(service.verify("user", "wrong"))
    }

    @Test
    fun `plaintext credential is rejected when plaintext is disallowed`() {
        val service = InMemoryAuthService(
            enabled = true,
            required = false,
            users = mapOf("user" to "password"),
            allowPlaintextPasswords = false,
        )

        assertFalse(service.verify("user", "password"))
    }

    @Test
    fun `plaintext credential can verify when explicitly allowed`() {
        val service = InMemoryAuthService(
            enabled = true,
            required = false,
            users = mapOf("user" to "password"),
            allowPlaintextPasswords = true,
        )

        assertTrue(service.verify("user", "password"))
    }
}
