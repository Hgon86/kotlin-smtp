package com.crinity.kotlinsmtp.config

import java.io.File

data class SslConfig(
    var enabled: Boolean = false,
    var certChainFile: String? = null,
    var privateKeyFile: String? = null
) {
    fun getCertChainFile(): File? = certChainFile?.let { File(it).takeIf { f -> f.exists() } }
    fun getPrivateKeyFile(): File? = privateKeyFile?.let { File(it).takeIf { f -> f.exists() } }
}
