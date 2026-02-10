package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.relay.api.*
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

private val log = KotlinLogging.logger {}

/**
 * 경로 선택기(`RelayRouteResolver`) 결과에 따라
 * MX 직접 전송 또는 Smart Host 전송을 수행하는 릴레이 구현.
 *
 * @property routeResolver 요청별 라우팅 경로 선택기
 * @property mxRelay MX 기반 전송 구현체
 * @property tls Smart Host 전송 시 기본 TLS 정책
 * @property dispatcherIO 블로킹 I/O 실행 디스패처
 */
class JakartaMailRoutingMailRelay(
    private val routeResolver: RelayRouteResolver,
    private val mxRelay: MailRelay,
    private val tls: OutboundTlsConfig,
    private val dispatcherIO: CoroutineDispatcher = Dispatchers.IO,
) : MailRelay {

    override suspend fun relay(request: RelayRequest): RelayResult {
        return when (val route = routeResolver.resolve(request)) {
            RelayRoute.DirectMx -> mxRelay.relay(request)
            is RelayRoute.SmartHost -> relayViaSmartHost(request, route)
        }
    }

    private suspend fun relayViaSmartHost(request: RelayRequest, route: RelayRoute.SmartHost): RelayResult =
        withContext(dispatcherIO) {
            val host = route.host.trim()
            if (host.isEmpty()) {
                throw RelayPermanentException("Smart host is blank (msgId=${request.messageId})")
            }
            if (route.port !in 1..65535) {
                throw RelayPermanentException("Invalid smart host port=${route.port} (msgId=${request.messageId})")
            }

            val recipientForSend = AddressUtils.normalizeDomainInAddress(request.recipient)
            val senderForSend = request.envelopeSender
                ?.takeIf { it.isNotBlank() }
                ?.let { AddressUtils.normalizeDomainInAddress(it) }

            val propsForParsing = Properties().apply {
                this["mail.mime.allowutf8"] = "true"
            }
            val message = request.rfc822.openStream().use { input ->
                MimeMessage(Session.getInstance(propsForParsing), input)
            }.apply {
                // Message-ID 헤더가 없으면 자동 생성 (RFC 5322 권장사항)
                if (getHeader("Message-ID") == null) {
                    val senderDomain = senderForSend?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: "localhost"
                    setHeader("Message-ID", "<${UUID.randomUUID()}@$senderDomain>")
                    log.debug { "Generated Message-ID for message to $recipientForSend" }
                }
            }

            try {
                val effectiveStartTlsEnabled = route.startTlsEnabled ?: tls.startTlsEnabled
                val effectiveStartTlsRequired = route.startTlsRequired ?: tls.startTlsRequired
                val effectiveCheckServerIdentity = route.checkServerIdentity ?: tls.checkServerIdentity
                val effectiveTrustAll = route.trustAll ?: tls.trustAll
                val effectiveTrustHosts = route.trustHosts ?: tls.trustHosts

                val props = Properties().apply {
                    this["mail.smtp.host"] = host
                    this["mail.smtp.port"] = route.port.toString()
                    this["mail.smtp.connectiontimeout"] = tls.connectTimeoutMs.toString()
                    this["mail.smtp.timeout"] = tls.readTimeoutMs.toString()
                    this["mail.smtp.quitwait"] = "false"
                    this["mail.smtp.from"] = (senderForSend ?: "").trim()

                    this["mail.mime.allowutf8"] = "true"
                    this["mail.smtp.allowutf8"] = "true"

                    this["mail.smtp.starttls.enable"] = effectiveStartTlsEnabled.toString()
                    this["mail.smtp.starttls.required"] = effectiveStartTlsRequired.toString()

                    this["mail.smtp.ssl.checkserveridentity"] = effectiveCheckServerIdentity.toString()
                    when {
                        effectiveTrustAll -> this["mail.smtp.ssl.trust"] = "*"
                        effectiveTrustHosts.isNotEmpty() -> this["mail.smtp.ssl.trust"] =
                            effectiveTrustHosts.joinToString(" ")
                    }

                    this["mail.smtp.auth"] = (!route.username.isNullOrBlank()).toString()
                }

                Session.getInstance(props).getTransport("smtp").use { transport ->
                    log.info {
                        "Attempting smart-host relay to $host:${route.port} (auth=${!route.username.isNullOrBlank()})"
                    }
                    transport.connect(host, route.port, route.username, route.password)
                    val rcptAddr = InternetAddress().apply { address = recipientForSend }
                    transport.sendMessage(message, arrayOf(rcptAddr))
                    log.info { "Smart-host relay succeeded to $recipientForSend via $host:${route.port}" }
                    RelayResult(remoteHost = host, remotePort = route.port)
                }
            } catch (e: Exception) {
                log.warn(e) {
                    "Smart-host relay failed (server=$host port=${route.port} auth=${request.authenticated} msgId=${request.messageId})"
                }
                throw RelayTransientException(
                    "Smart-host relay failed (server=$host port=${route.port} msgId=${request.messageId})",
                    e,
                )
            }
        }
}
