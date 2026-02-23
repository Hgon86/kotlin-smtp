package io.github.kotlinsmtp.config

import java.io.File

/**
 * Inbound TLS configuration for SMTP listeners.
 *
 * @property enabled Enables TLS features such as STARTTLS and implicit TLS.
 * @property certChainFile PEM certificate chain file path.
 * @property privateKeyFile PEM private key file path.
 * @property minTlsVersion Minimum accepted TLS version.
 * @property handshakeTimeoutMs TLS handshake timeout in milliseconds.
 * @property cipherSuites Explicit TLS cipher suite list. Empty uses JVM defaults.
 */
data class SslConfig(
    /**
     * Enables TLS features such as STARTTLS and implicit TLS.
     */
    var enabled: Boolean = false,

    /**
     * PEM certificate chain file path.
     */
    var certChainFile: String? = null,

    /**
     * PEM private key file path.
     */
    var privateKeyFile: String? = null,

    /**
     * Minimum accepted TLS version.
     */
    var minTlsVersion: String = "TLSv1.2", // TLSv1.2, TLSv1.3

    /**
     * TLS handshake timeout in milliseconds.
     */
    var handshakeTimeoutMs: Int = 30_000,

    /**
     * Explicit TLS cipher suite list. Empty list uses JVM defaults.
     */
    var cipherSuites: List<String> = emptyList(),
) {
    fun getCertChainFile(): File? = certChainFile?.let { File(it).takeIf { f -> f.exists() } }
    fun getPrivateKeyFile(): File? = privateKeyFile?.let { File(it).takeIf { f -> f.exists() } }

    fun validate() {
        if (!enabled) return

        val normalizedMinTls = minTlsVersion.trim()
        require(normalizedMinTls in setOf("TLSv1.2", "TLSv1.3")) {
            "smtp.ssl.minTlsVersion must be TLSv1.2 or TLSv1.3"
        }
        minTlsVersion = normalizedMinTls
        require(handshakeTimeoutMs > 0) {
            "smtp.ssl.handshakeTimeoutMs must be > 0"
        }
        require(cipherSuites.none { it.isBlank() }) {
            "smtp.ssl.cipherSuites must not contain blank values"
        }

        require(!certChainFile.isNullOrBlank()) {
            "smtp.ssl.certChainFile must be configured when smtp.ssl.enabled=true"
        }
        require(!privateKeyFile.isNullOrBlank()) {
            "smtp.ssl.privateKeyFile must be configured when smtp.ssl.enabled=true"
        }
        require(File(certChainFile!!).exists()) {
            "smtp.ssl.certChainFile does not exist: ${certChainFile}"
        }
        require(File(privateKeyFile!!).exists()) {
            "smtp.ssl.privateKeyFile does not exist: ${privateKeyFile}"
        }
    }
}
