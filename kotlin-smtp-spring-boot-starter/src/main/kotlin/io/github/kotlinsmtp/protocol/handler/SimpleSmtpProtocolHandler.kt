package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.storage.MessageStore
import io.github.kotlinsmtp.storage.SentArchiveMode
import io.github.kotlinsmtp.storage.SentMessageStore
import io.github.kotlinsmtp.spool.MailDeliveryService
import io.github.kotlinsmtp.spool.MailSpooler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

private val log = KotlinLogging.logger {}

class SimpleSmtpProtocolHandler(
    private val dispatcherIO: CoroutineDispatcher = Dispatchers.IO,
    private val messageStore: MessageStore,
    private val sentMessageStore: SentMessageStore,
    private val sentArchiveMode: SentArchiveMode,
    private val relayEnabled: Boolean,
    private val deliveryService: MailDeliveryService,
    private val spooler: MailSpooler?,
) : SmtpProtocolHandler() {

    private var sender: String? = null
    private val recipients = mutableSetOf<String>()

    override suspend fun from(sender: String) {
        this.sender = sender
        log.info { "Mail from: $sender" }
    }

    override suspend fun to(recipient: String) {
        val domain = recipient.substringAfterLast('@', "")
        val isLocal = deliveryService.isLocalDomain(domain)
        if (!isLocal) {
            if (!relayEnabled) {
                // RFC 관례에 맞춰 5.7.1로 릴레이 거부를 명확히 표현합니다.
                throw io.github.kotlinsmtp.exception.SmtpSendResponse(550, "5.7.1 Relay access denied")
            }
            // 오픈 릴레이 방지: RCPT 단계에서 정책을 조기 검증합니다.
            deliveryService.enforceRelayPolicySmtp(
                sender = sender?.ifBlank { null },
                recipient = recipient,
                authenticated = sessionData.isAuthenticated,
                peerAddress = sessionData.peerAddress,
            )
        }
        recipients.add(recipient)
        log.info { "Recipient added: $recipient" }
    }

    override suspend fun data(inputStream: InputStream, size: Long) = withContext(dispatcherIO) {
        if (recipients.isEmpty()) {
            log.warn { "No recipients specified, skipping message" }
            return@withContext
        }

        val messageId = UUID.randomUUID().toString().take(8)
        log.info { "Receiving message data (messageId=$messageId)" }

        try {
            val receivedValue = buildReceivedValue(
                byServer = sessionData.serverHostname,
                fromPeer = sessionData.peerAddress,
                // 기능 우선: 인증 여부를 Received 헤더에 표시(ESMTPA/ESMTPSA)
                // TODO: SMTPUTF8 등의 세부 정보까지 표준적으로 표현하는 방식은 추후 정리
                withInfo = when {
                    sessionData.tlsActive && sessionData.isAuthenticated -> "ESMTPSA"
                    sessionData.tlsActive -> "ESMTPS"
                    sessionData.isAuthenticated -> "ESMTPA"
                    else -> "ESMTP"
                },
                forRecipient = if (recipients.size == 1) recipients.first() else null,
                idValue = messageId
            )

            // 수신 원문을 "Received 헤더 + 원문"으로 저장합니다.
            // TODO(storage): DB/S3 등 최종 저장소로 바뀌면 MessageStore 구현체만 교체합니다.
            val tempFile: Path = messageStore.storeRfc822(
                messageId = messageId,
                receivedHeaderValue = receivedValue,
                rawInput = inputStream,
            )
            archiveSentMailboxCopy(
                tempFile = tempFile,
                messageId = messageId,
            )

            if (spooler != null) {
                spooler.enqueue(
                    rawMessagePath = tempFile,
                    sender = sender,
                    recipients = recipients.toList(),
                    messageId = messageId,
                    authenticated = sessionData.isAuthenticated,
                    peerAddress = sessionData.peerAddress,
                    dsnEnvid = sessionData.dsnEnvid,
                    dsnRet = sessionData.dsnRet,
                    rcptDsn = sessionData.rcptDsnView,
                )
                log.info { "Enqueued to spool only (no immediate delivery)." }
                Files.deleteIfExists(tempFile)
            } else {
                deliverSynchronously(tempFile, messageId)
                Files.deleteIfExists(tempFile)
            }
        } catch (e: Exception) {
            log.error(e) { "Error processing message data" }
            throw e
        }
    }

    private suspend fun deliverSynchronously(tempFile: Path, messageId: String) {
        recipients.forEach { recipient ->
            val domain = recipient.substringAfterLast('@', "")
            val isLocal = deliveryService.isLocalDomain(domain)
            runCatching {
                if (isLocal) deliveryService.deliverLocal(recipient, tempFile)
                else deliveryService.relayExternal(
                    envelopeSender = sender,
                    recipient = recipient,
                    rawPath = tempFile,
                    messageId = messageId,
                    authenticated = sessionData.isAuthenticated,
                    peerAddress = sessionData.peerAddress,
                    generateDsnOnFailure = true,
                    rcptNotify = sessionData.rcptDsnView[recipient]?.notify,
                    rcptOrcpt = sessionData.rcptDsnView[recipient]?.orcpt,
                    dsnEnvid = sessionData.dsnEnvid,
                    dsnRet = sessionData.dsnRet,
                )
            }.onSuccess {
                log.info { "Delivered to ${if (isLocal) "local" else "external"} recipient: $recipient" }
            }.onFailure {
                log.error(it) { "Failed to deliver synchronously: $recipient" }
            }
        }
    }

    /**
     * 인증 세션에서 제출된 메시지를 보낸 메일함에 기록합니다.
     *
     * 저장 실패는 SMTP 트랜잭션 실패로 간주하지 않고 경고 로그만 남깁니다.
     *
     * @param tempFile 임시 원문 파일 경로
     * @param messageId 메시지 식별자
     */
    private fun archiveSentMailboxCopy(tempFile: Path, messageId: String) {
        if (!shouldArchiveSentMessage()) return

        runCatching {
            sentMessageStore.archiveSubmittedMessage(
                rawPath = tempFile,
                envelopeSender = sender,
                submittingUser = sessionData.authenticatedUsername,
                recipients = recipients.toList(),
                messageId = messageId,
                authenticated = sessionData.isAuthenticated,
            )
        }.onFailure { e ->
            log.warn(e) { "Failed to archive submitted message for sender=${sender ?: "?"} messageId=$messageId" }
        }
    }

    /**
     * 현재 트랜잭션이 보낸 메일함 저장 대상인지 판별합니다.
     *
     * @return 저장 대상 여부
     */
    private fun shouldArchiveSentMessage(): Boolean {
        val hasExternalRecipient = recipients.any { recipient ->
            val domain = recipient.substringAfterLast('@', "")
            !deliveryService.isLocalDomain(domain)
        }

        return when (sentArchiveMode) {
            SentArchiveMode.DISABLED -> false
            SentArchiveMode.AUTHENTICATED_ONLY -> sessionData.isAuthenticated
            SentArchiveMode.TRUSTED_SUBMISSION -> sessionData.isAuthenticated || hasExternalRecipient
        }
    }

    override suspend fun done() {
        sender = null
        recipients.clear()
        log.info { "Transaction completed" }
    }

    private fun buildReceivedValue(
        byServer: String?,
        fromPeer: String?,
        withInfo: String?,
        forRecipient: String? = null,
        idValue: String? = null,
    ): String {
        fun sanitizeHeaderValue(value: String?): String? = value
            ?.replace(Regex("[\r\n]+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val dateStr = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.ZonedDateTime.now())
        val byPart = sanitizeHeaderValue(byServer)?.let { "by $it" }
        val fromPart = sanitizeHeaderValue(fromPeer)?.let { "from $it" }
        val withPart = sanitizeHeaderValue(withInfo)?.let { "with $it" }
        val forPart = sanitizeHeaderValue(forRecipient)?.let { "for <$it>" }
        val idPart = sanitizeHeaderValue(idValue)?.let { "id $it" }
        return listOfNotNull(fromPart, byPart, idPart, withPart, forPart, "; $dateStr").joinToString(" ")
    }
}
