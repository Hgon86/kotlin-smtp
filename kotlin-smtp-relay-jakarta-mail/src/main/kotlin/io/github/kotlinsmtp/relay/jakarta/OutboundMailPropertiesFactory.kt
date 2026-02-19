package io.github.kotlinsmtp.relay.jakarta

import java.util.Properties

/**
 * Factory helpers for Jakarta Mail SMTP properties.
 */
internal object OutboundMailPropertiesFactory {
    fun create(
        host: String,
        port: Int,
        sender: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        tls: OutboundTlsPolicyApplier.ResolvedTls,
        enableAuth: Boolean = false,
    ): Properties = Properties().apply {
        this["mail.smtp.host"] = host
        this["mail.smtp.port"] = port.toString()
        this["mail.smtp.connectiontimeout"] = connectTimeoutMs.toString()
        this["mail.smtp.timeout"] = readTimeoutMs.toString()
        this["mail.smtp.quitwait"] = "false"
        this["mail.smtp.from"] = (sender ?: "").trim()

        this["mail.mime.allowutf8"] = "true"
        this["mail.smtp.allowutf8"] = "true"
        this["mail.smtp.allow8bitmime"] = "true"

        this["mail.smtp.starttls.enable"] = tls.startTlsEnabled.toString()
        this["mail.smtp.starttls.required"] = tls.startTlsRequired.toString()
        this["mail.smtp.ssl.checkserveridentity"] = tls.checkServerIdentity.toString()
        when {
            tls.trustAll -> this["mail.smtp.ssl.trust"] = "*"
            tls.trustHosts.isNotEmpty() -> this["mail.smtp.ssl.trust"] = tls.trustHosts.joinToString(" ")
        }

        this["mail.smtp.auth"] = enableAuth.toString()
    }
}
