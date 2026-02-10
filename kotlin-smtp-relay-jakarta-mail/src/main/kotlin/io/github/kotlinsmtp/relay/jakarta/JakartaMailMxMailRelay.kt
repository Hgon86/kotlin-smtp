package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.relay.api.*
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.*
import java.util.*

private val log = KotlinLogging.logger {}

/**
 * dnsjava MX 조회 + jakarta-mail(angus) 기반의 기본 outbound relay 구현.
 */
class JakartaMailMxMailRelay(
    private val dispatcherIO: CoroutineDispatcher = Dispatchers.IO,
    private val tls: OutboundTlsConfig,
) : MailRelay {

    private data class MxRecord(val priority: Int, val host: String)

    private val dnsCache = Cache(DClass.IN).apply {
        setMaxEntries(10_000)
    }

    override suspend fun relay(request: RelayRequest): RelayResult = withContext(dispatcherIO) {
        val recipientForSend = AddressUtils.normalizeDomainInAddress(request.recipient)
        val senderForSend = request.envelopeSender
            ?.takeIf { it.isNotBlank() }
            ?.let { AddressUtils.normalizeDomainInAddress(it) }

        val rawDomain = recipientForSend.substringAfterLast('@')
        val normalizedDomain = AddressUtils.normalizeDomain(rawDomain) ?: rawDomain
        val mxRecords = lookupMxRecords(normalizedDomain)

        if (mxRecords.isEmpty()) {
            throw RelayPermanentException("No MX records for domain: $normalizedDomain (msgId=${request.messageId})")
        }

        val ports = tls.ports.ifEmpty { listOf(25) }
        var lastException: Exception? = null

        val propsForParsing = Properties().apply {
            // SMTPUTF8/UTF-8 헤더 파싱을 위해 허용(최소 구현)
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

        for (mx in mxRecords.sortedBy { it.priority }) {
            val targetServer = mx.host
            for (port in ports) {
                try {
                    val props = Properties().apply {
                        this["mail.smtp.host"] = targetServer
                        this["mail.smtp.port"] = port.toString()
                        this["mail.smtp.connectiontimeout"] = tls.connectTimeoutMs.toString()
                        this["mail.smtp.timeout"] = tls.readTimeoutMs.toString()
                        this["mail.smtp.quitwait"] = "false"

                        // SMTP 엔벌로프(리턴-패스) 설정
                        this["mail.smtp.from"] = (senderForSend ?: "").trim()

                        // SMTPUTF8/UTF-8
                        this["mail.mime.allowutf8"] = "true"
                        this["mail.smtp.allowutf8"] = "true"

                        // STARTTLS
                        this["mail.smtp.starttls.enable"] = tls.startTlsEnabled.toString()
                        this["mail.smtp.starttls.required"] = tls.startTlsRequired.toString()

                        // TLS 검증
                        this["mail.smtp.ssl.checkserveridentity"] = tls.checkServerIdentity.toString()
                        when {
                            tls.trustAll -> this["mail.smtp.ssl.trust"] = "*"
                            tls.trustHosts.isNotEmpty() -> this["mail.smtp.ssl.trust"] =
                                tls.trustHosts.joinToString(" ")
                        }
                    }

                    val session = Session.getInstance(props)
                    session.getTransport("smtp").use { transport ->
                        log.info { "Attempting to connect to $targetServer on port $port (mxPref=${mx.priority})" }
                        transport.connect()
                        val rcptAddr = InternetAddress().apply { address = recipientForSend }
                        transport.sendMessage(message, arrayOf(rcptAddr))
                        log.info {
                            "Successfully relayed message to $recipientForSend via $targetServer:$port (mxPref=${mx.priority})"
                        }
                        return@withContext RelayResult(remoteHost = targetServer, remotePort = port)
                    }
                } catch (e: Exception) {
                    log.warn(e) {
                        "Relay attempt failed (server=$targetServer port=$port mxPref=${mx.priority} auth=${request.authenticated} msgId=${request.messageId} sender=${
                            senderForSend?.take(
                                64
                            ) ?: "null"
                        })"
                    }
                    lastException = e
                }
            }
        }

        val last = lastException
        if (last != null) {
            throw RelayTransientException("Relay failed (domain=$normalizedDomain msgId=${request.messageId})", last)
        }
        throw RelayTransientException("Relay failed (domain=$normalizedDomain msgId=${request.messageId})")
    }

    private fun lookupMxRecords(domain: String): List<MxRecord> {
        return runCatching {
            val name = toDnsName(domain)
            val mx = lookupRecords(name, Type.MX)
                .mapNotNull { it as? MXRecord }
                .map { MxRecord(priority = it.priority, host = it.target.toString().removeSuffix(".")) }
                .sortedBy { it.priority }
            if (mx.isNotEmpty()) return mx

            val hasA = lookupRecords(name, Type.A).any { it is ARecord }
            val hasAAAA = lookupRecords(name, Type.AAAA).any { it is AAAARecord }
            if (hasA || hasAAAA) listOf(MxRecord(priority = 0, host = domain)) else emptyList()
        }.onFailure { e ->
            log.error(e) { "Error looking up MX/A/AAAA records for $domain" }
        }.getOrDefault(emptyList())
    }

    private fun toDnsName(domain: String): Name {
        val d = domain.trim().removeSuffix(".")
        return Name.fromString("$d.")
    }

    private fun lookupRecords(name: Name, type: Int): List<Record> {
        val lookup = org.xbill.DNS.Lookup(name, type)
        lookup.setCache(dnsCache)
        return (lookup.run() ?: emptyArray()).toList()
    }
}

/**
 * 아웃바운드 릴레이(TCP 25 등)에서의 TLS/STARTTLS 정책
 */
data class OutboundTlsConfig(
    val ports: List<Int> = listOf(25),
    val startTlsEnabled: Boolean = true,
    val startTlsRequired: Boolean = false,
    val checkServerIdentity: Boolean = true,
    val trustAll: Boolean = false,
    val trustHosts: List<String> = emptyList(),
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 15_000,
)
