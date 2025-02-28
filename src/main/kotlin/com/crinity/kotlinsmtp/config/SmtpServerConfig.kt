package com.crinity.kotlinsmtp.config

import com.crinity.kotlinsmtp.protocol.handler.SimpleSmtpProtocolHandler
import com.crinity.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import com.crinity.kotlinsmtp.server.SmtpServer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

@Configuration
@ConfigurationProperties(prefix = "smtp")
class SmtpServerConfig {
    // 서버 기본 설정
    var port: Int = 25
    var hostname: String = "localhost"
    var serviceName: String = "kotlin-smtp"

    // SSL/TLS 설정
    var ssl: SslConfig = SslConfig()

    // 메일 저장 및 릴레이 설정
    var storage: StorageConfig = StorageConfig()
    var relay: RelayConfig = RelayConfig()

    data class StorageConfig(
        var mailboxDir: String = "C:\\smtp-server\\mailboxes",
        var tempDir: String = "C:\\smtp-server\\temp-mails"
    ) {
        // Path 객체로 변환
        val mailboxPath: Path get() = Path.of(mailboxDir)
        val tempPath: Path get() = Path.of(tempDir)
    }

    data class RelayConfig(
        var enabled: Boolean = false,
        var localDomain: String = "mydomain.com",
    )

    @Bean
    fun smtpTransactionHandler(): SmtpProtocolHandler =
        SimpleSmtpProtocolHandler(
            localDomain = relay.localDomain,
            mailboxDir = storage.mailboxPath,
            tempDir = storage.tempPath,
            relayEnabled = relay.enabled,
        )

    @Bean
    fun smtpServer(): SmtpServer =
        SmtpServer(
            port = port,
            hostname = hostname,
            serviceName = serviceName,
            certChainFile = ssl.getCertChainFile(),
            privateKeyFile = ssl.getPrivateKeyFile(),
            transactionHandlerCreator = { smtpTransactionHandler() }
        )

    // 나중에 추가 될 설정 예시
    /*data class AuthConfig(
        var required: Boolean = false,
        var methods: List<String> = listOf("PLAIN", "LOGIN"),
        var userFile: String? = null,
    )

    data class LimitsConfig(
        var maxMessageSize: Long = 10 * 1024 * 1024,// 10MB
        var maxRecipients: Int = 100,
        var connectionTimeout: Int = 5 * 60, // 5분
        var maxConnections: Int = 100,
    )*/
}