package io.github.kotlinsmtp.config

import java.io.File

data class SslConfig(
    var enabled: Boolean = false,
    var certChainFile: String? = null,
    var privateKeyFile: String? = null,
    // TLS 하드닝 설정
    var minTlsVersion: String = "TLSv1.2", // TLSv1.2, TLSv1.3
    var handshakeTimeoutMs: Int = 30_000, // 30초
    var cipherSuites: List<String> = emptyList(), // 빈 리스트면 JVM 기본값 사용
) {
    fun getCertChainFile(): File? = certChainFile?.let { File(it).takeIf { f -> f.exists() } }
    fun getPrivateKeyFile(): File? = privateKeyFile?.let { File(it).takeIf { f -> f.exists() } }
}
