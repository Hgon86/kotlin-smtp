package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.storage.MessageStore
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
            deliveryService.enforceRelayPolicySmtp(sender?.ifBlank { null }, recipient, sessionData.isAuthenticated)
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

            if (spooler != null) {
                spooler.enqueue(
                    rawMessagePath = tempFile,
                    sender = sender,
                    recipients = recipients.toList(),
                    messageId = messageId,
                    authenticated = sessionData.isAuthenticated,
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
