package com.crinity.kotlinsmtp.spool

import com.crinity.kotlinsmtp.exception.SmtpSendResponse
import com.crinity.kotlinsmtp.mail.LocalMailboxManager
import com.crinity.kotlinsmtp.mail.MailRelay
import com.crinity.kotlinsmtp.model.RcptDsn
import com.crinity.kotlinsmtp.relay.DsnService
import com.crinity.kotlinsmtp.utils.AddressUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private val log = KotlinLogging.logger {}

class MailDeliveryService(
    private val localMailboxManager: LocalMailboxManager,
    private val mailRelay: MailRelay,
    private val localDomain: String,
    private val allowedSenderDomains: List<String> = emptyList(),
    private val requireAuthForRelay: Boolean = false,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var dsnService: DsnService? = null
    private val normalizedLocalDomain = AddressUtils.normalizeDomain(localDomain) ?: localDomain.lowercase()
    private val normalizedAllowedDomains =
        allowedSenderDomains.mapNotNull { AddressUtils.normalizeDomain(it) ?: it.lowercase() }.toSet()

    fun attachDsnService(service: DsnService) { this.dsnService = service }

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
        generateDsnOnFailure: Boolean = true,
        rcptNotify: String? = null,
        rcptOrcpt: String? = null,
        dsnEnvid: String? = null,
        dsnRet: String? = null,
    ) = withContext(dispatcher) {
        enforceRelayPolicySmtp(envelopeSender, authenticated)
        val message = Files.newInputStream(rawPath).use { input ->
            val props = Properties().apply {
                // SMTPUTF8/UTF-8 헤더 파싱을 위해 허용(최소 구현)
                // TODO: 구현체 버전별 공식 문서 확인 후 고정
                this["mail.mime.allowutf8"] = "true"
            }
            MimeMessage(Session.getInstance(props), input)
        }
        runCatching { mailRelay.relayMessage(envelopeSender, recipient, message, messageId, authenticated) }.getOrElse { ex ->
            log.warn(ex) { "Relay failed (rcpt=$recipient msgId=$messageId), dsnOnFailure=$generateDsnOnFailure" }
            if (generateDsnOnFailure && shouldSendFailureDsn(rcptNotify)) {
                dsnService?.sendPermanentFailure(
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
     */
    fun enforceRelayPolicySmtp(sender: String?, authenticated: Boolean) {
        if (requireAuthForRelay && !authenticated) {
            throw SmtpSendResponse(530, "5.7.0 Authentication required")
        }
        if (normalizedAllowedDomains.isEmpty()) return
        val domain = sender?.substringAfterLast('@')?.let { AddressUtils.normalizeDomain(it) }
        if ((domain == null || domain !in normalizedAllowedDomains) && !authenticated) {
            throw SmtpSendResponse(550, "5.7.1 Relay access denied")
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
