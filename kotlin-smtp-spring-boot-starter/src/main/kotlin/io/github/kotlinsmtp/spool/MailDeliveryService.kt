package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.exception.SmtpSendResponse
import io.github.kotlinsmtp.mail.LocalMailboxManager
import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessDecision
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import io.github.kotlinsmtp.relay.api.RelayRequest
import io.github.kotlinsmtp.util.AddressUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger {}

class MailDeliveryService(
    private val localMailboxManager: LocalMailboxManager,
    private val mailRelay: MailRelay,
    private val relayAccessPolicy: RelayAccessPolicy,
    private val dsnSenderProvider: () -> DsnSender?,
    private val localDomain: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val normalizedLocalDomain = AddressUtils.normalizeDomain(localDomain) ?: localDomain.lowercase()

    fun isLocalDomain(domain: String): Boolean {
        val normalized = AddressUtils.normalizeDomain(domain) ?: domain.lowercase()
        return normalized == normalizedLocalDomain
    }

    suspend fun deliverLocal(recipient: String, rawPath: Path) = withContext(dispatcher) {
        val username = recipient.substringBeforeLast('@')
        localMailboxManager.deliverToLocalMailbox(username, rawPath)
    }

    suspend fun relayExternal(
        envelopeSender: String?,
        recipient: String,
        rawPath: Path,
        messageId: String,
        authenticated: Boolean,
        peerAddress: String? = null,
        generateDsnOnFailure: Boolean = true,
        rcptNotify: String? = null,
        rcptOrcpt: String? = null,
        dsnEnvid: String? = null,
        dsnRet: String? = null,
    ) = withContext(dispatcher) {
        enforceRelayPolicySmtp(envelopeSender, recipient, authenticated, peerAddress)
        val request = RelayRequest(
            messageId = messageId,
            envelopeSender = envelopeSender,
            recipient = recipient,
            authenticated = authenticated,
            rfc822 = io.github.kotlinsmtp.relay.api.Rfc822Source {
                Files.newInputStream(rawPath)
            },
        )

        runCatching { mailRelay.relay(request) }.getOrElse { ex ->
            log.warn(ex) { "Relay failed (rcpt=$recipient msgId=$messageId), dsnOnFailure=$generateDsnOnFailure" }
            if (generateDsnOnFailure && shouldSendFailureDsn(rcptNotify)) {
                dsnSenderProvider()?.sendPermanentFailure(
                    sender = envelopeSender,
                    failedRecipients = listOf(recipient to ex.message.orEmpty()),
                    originalMessageId = messageId,
                    originalMessagePath = rawPath,
                    dsnEnvid = dsnEnvid,
                    dsnRet = dsnRet,
                    rcptDsn = mapOf(recipient to RcptDsn(notify = rcptNotify, orcpt = rcptOrcpt)),
                )
            }
            throw ex
        }
    }

    /**
     * 외부 릴레이 허용 정책을 SMTP 응답 코드로 표현해 거부합니다.
     * - 530 5.7.0: 인증 필요
     * - 550 5.7.1: 릴레이 거부(정책)
     *
     * NOTE: 여기서 던진 예외는 세션 레벨에서는 즉시 거부 응답으로,
     *       스풀/동기 전달에서는 DSN 처리 경로로 흘러갈 수 있습니다.
     *
     * @param sender envelope sender
     * @param recipient 수신자
     * @param authenticated 인증 여부
     * @param peerAddress 클라이언트 주소
     */
    fun enforceRelayPolicySmtp(
        sender: String?,
        recipient: String,
        authenticated: Boolean,
        peerAddress: String? = null,
    ) {
        val decision = relayAccessPolicy.evaluate(
            io.github.kotlinsmtp.relay.api.RelayAccessContext(
                envelopeSender = sender?.ifBlank { null },
                recipient = recipient,
                authenticated = authenticated,
                peerAddress = peerAddress,
            )
        )
        when (decision) {
            is RelayAccessDecision.Allowed -> Unit
            is RelayAccessDecision.Denied -> {
                when (decision.reason) {
                    io.github.kotlinsmtp.relay.api.RelayDeniedReason.AUTH_REQUIRED ->
                        throw SmtpSendResponse(530, "5.7.0 Authentication required")

                    io.github.kotlinsmtp.relay.api.RelayDeniedReason.SENDER_DOMAIN_NOT_ALLOWED,
                    io.github.kotlinsmtp.relay.api.RelayDeniedReason.OTHER_POLICY ->
                        throw SmtpSendResponse(550, "5.7.1 Relay access denied")
                }
            }
        }
    }

    /**
     * RFC 3461 NOTIFY 최소 반영: 실패 DSN 억제 규칙
     * - NOTIFY=NEVER: DSN 금지
     * - NOTIFY에 FAILURE 포함: 실패 DSN 허용
     * - 미지정/기타: 실사용 편의상 실패 DSN은 허용(보수적으로 발송)
     */
    private fun shouldSendFailureDsn(notify: String?): Boolean {
        val raw = notify?.trim().orEmpty()
        if (raw.isEmpty()) return true
        val tokens = raw.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        if ("NEVER" in tokens) return false
        return "FAILURE" in tokens
    }
}
