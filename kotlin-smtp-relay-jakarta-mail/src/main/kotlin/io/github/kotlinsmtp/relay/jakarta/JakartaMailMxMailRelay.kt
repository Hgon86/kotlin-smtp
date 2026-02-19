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
 * Default outbound relay implementation based on dnsjava MX lookup + jakarta-mail (angus).
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
            // Allow SMTPUTF8/UTF-8 header parsing (minimal implementation)
            this["mail.mime.allowutf8"] = "true"
        }
        val message = request.rfc822.openStream().use { input ->
            MimeMessage(Session.getInstance(propsForParsing), input)
        }.apply {
            // Auto-generate Message-ID header when missing (RFC 5322 recommendation)
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

                        // Set SMTP envelope (return-path)
                        this["mail.smtp.from"] = (senderForSend ?: "").trim()

                        // SMTPUTF8/UTF-8
                        this["mail.mime.allowutf8"] = "true"
                        this["mail.smtp.allowutf8"] = "true"

                        // STARTTLS
                        this["mail.smtp.starttls.enable"] = tls.startTlsEnabled.toString()
                        this["mail.smtp.starttls.required"] = tls.startTlsRequired.toString()

                        // TLS verification
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
 * TLS/STARTTLS policy for outbound relay (TCP 25, etc.)
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
