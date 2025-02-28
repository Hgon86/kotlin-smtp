package com.crinity.kotlinsmtp.protocol.handler

import com.crinity.kotlinsmtp.mail.LocalMailboxManager
import com.crinity.kotlinsmtp.mail.MailParser
import com.crinity.kotlinsmtp.mail.MailRelay
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * SMTP 트랜잭션을 처리하는 핸들러 클래스
 *
 * 메일 수신, 로컬 메일박스 전달, 외부 서버 릴레이 기능을 담당합니다.
 *
 * 참고: 로컬 도메인 판단 로직은 추후 Redis로 대체 예정
 * Redis 구현 시 다음 기능이 필요합니다:
 * 1. 다중 로컬 도메인 지원 (여러 도메인을 로컬로 처리)
 * 2. 도메인별 메일박스 디렉토리 매핑
 * 3. 도메인 추가/삭제 실시간 반영
 */
private val log = KotlinLogging.logger {}

class SimpleSmtpProtocolHandler(
    private val dispatcherIO: CoroutineDispatcher = Dispatchers.IO,
    private val localDomain: String = "mydomain.com", // 단일 도메인 (추후 다중 도메인으로 확장 예정)
    private val mailboxDir: Path = Path.of("C:\\smtp-server\\mailboxes"),
    private val tempDir: Path = Path.of("C:\\smtp-server\\temp-mails"),
    private val relayEnabled: Boolean = true,
) : SmtpProtocolHandler() {

    private var sender: String? = null
    private val recipients = mutableSetOf<String>()
    private val mailParser by lazy { MailParser() }
    private val mailRelay by lazy { MailRelay(dispatcherIO) }
    private val mailboxManager by lazy { LocalMailboxManager(mailboxDir) }

    init {
        try {
            Files.createDirectories(mailboxDir)
            Files.createDirectories(tempDir)
        } catch (e: Exception) {
            log.error(e) { "Failed to create mail directories" }
            throw RuntimeException("Failed to initialize mail directories", e)
        }
    }

    override suspend fun from(sender: String) {
        this.sender = sender
        log.info { "Mail from: $sender" }
    }

    override suspend fun to(recipient: String) {
        recipients.add(recipient)
        log.info { "Recipient added: $recipient" }
    }

    override suspend fun data(inputStream: InputStream, size: Long) = withContext(dispatcherIO) {
        if (recipients.isEmpty()) {
            log.warn { "No recipients specified, skipping message" }
            return@withContext
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val messageId = UUID.randomUUID().toString().take(8)
        val tempFile = tempDir.resolve("mail_${timestamp}_${messageId}.tmp")

        log.info { "Receiving message data, saving to temporary file: $tempFile" }

        try {
            // 메시지 생성
            val message = mailParser.createMimeMessage(inputStream, sender, recipients)

            Files.newOutputStream(tempFile, StandardOpenOption.CREATE).use { output ->
                message.writeTo(output)
            }

            val results = recipients.map { recipient ->
                async {
                    runCatching {
                        processRecipient(recipient, tempFile, message)
                    }.onFailure { e ->
                        log.error(e) { "Failed to process recipient: $recipient" }
                    }
                }
            }.awaitAll()

            val successCount = results.count { it.isSuccess }
            log.info { "Message processing completed: $successCount/${recipients.size} recipients processed successfully" }
        } catch (e: Exception) {
            log.error(e) { "Error processing message data" }
            throw e
        } finally {
            runCatching {
                Files.deleteIfExists(tempFile)
            }.onFailure { e ->
                log.warn(e) { "Failed to delete temporary file: $tempFile" }
            }
        }
    }

    /**
     * 수신자에게 메일을 전달합니다.
     *
     * TODO: Redis 기반 도메인 관리로 대체 예정
     * 1. Redis에서 도메인 조회 (KEY: "local:domains")
     * 2. 도메인별 설정 조회 (KEY: "domain:config:{domain}")
     * 3. 도메인별 메일박스 디렉토리 매핑
     */
    private suspend fun processRecipient(recipient: String, tempFile: Path, message: MimeMessage) =
        withContext(dispatcherIO) {
            val domain = recipient.substringAfterLast('@', "")

            // TODO: Redis 기반 로컬 도메인 확인으로 대체 예정
            // val isLocalDomain = domainManager.isLocalDomain(domain)
            val isLocalDomain = domain.equals(localDomain, ignoreCase = true)

            when {
                isLocalDomain -> {
                    // TODO: 카프카 이벤트 발행으로 대체 예정
                    // val domainConfig = domainManager.getDomainConfig(domain)
                    // val domainMailboxDir = domainConfig.mailboxDir

                    val username = recipient.substringBeforeLast('@')
                    mailboxManager.deliverToLocalMailbox(username, tempFile)
                    log.info { "Delivered to local mailbox: $username (domain: $domain)" }
                }

                relayEnabled -> {
                    mailRelay.relayToExternalServer(recipient, message)
                    log.info { "Relayed to external recipient: $recipient" }
                }

                else -> {
                    log.warn { "Relay not enabled, skipping delivery to external recipient: $recipient" }
                }
            }
        }

    override suspend fun done() {
        sender = null
        recipients.clear()
        log.info { "Transaction completed" }
    }
}