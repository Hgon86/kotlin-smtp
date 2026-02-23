package io.github.kotlinsmtp.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SslConfigValidationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `validate fails when min TLS version is below policy baseline`() {
        val config = validSslConfig().apply {
            minTlsVersion = "TLSv1.1"
        }

        assertThrows(IllegalArgumentException::class.java) {
            config.validate()
        }
    }

    @Test
    fun `validate fails when handshake timeout is non-positive`() {
        val config = validSslConfig().apply {
            handshakeTimeoutMs = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            config.validate()
        }
    }

    @Test
    fun `validate fails when cipher suites contain blank values`() {
        val config = validSslConfig().apply {
            cipherSuites = listOf("TLS_AES_128_GCM_SHA256", " ")
        }

        assertThrows(IllegalArgumentException::class.java) {
            config.validate()
        }
    }

    @Test
    fun `validate succeeds with TLSv1_2 and existing key files`() {
        val config = validSslConfig().apply {
            minTlsVersion = "TLSv1.2"
            handshakeTimeoutMs = 30_000
            cipherSuites = listOf("TLS_AES_128_GCM_SHA256")
        }

        assertDoesNotThrow {
            config.validate()
        }
    }

    @Test
    fun `validate succeeds with TLSv1_3 and existing key files`() {
        val config = validSslConfig().apply {
            minTlsVersion = "TLSv1.3"
            handshakeTimeoutMs = 30_000
        }

        assertDoesNotThrow {
            config.validate()
        }
    }

    private fun validSslConfig(): SslConfig {
        val cert = tempDir.resolve("cert.pem")
        val key = tempDir.resolve("key.pem")
        Files.writeString(cert, "dummy-cert")
        Files.writeString(key, "dummy-key")

        return SslConfig(
            enabled = true,
            certChainFile = cert.toString(),
            privateKeyFile = key.toString(),
            minTlsVersion = "TLSv1.2",
            handshakeTimeoutMs = 30_000,
            cipherSuites = emptyList(),
        )
    }
}
