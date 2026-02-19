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
import java.util.Properties

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
        var lastException: RelayException? = null

        val propsForParsing = Properties().apply {
            // Allow SMTPUTF8/UTF-8 header parsing (minimal implementation)
            this["mail.mime.allowutf8"] = "true"
        }
        val message = request.rfc822.openStream().use { input ->
            MimeMessage(Session.getInstance(propsForParsing), input)
        }
        val supplemented = OutboundMessageHeaderSupplement.ensureRequiredHeaders(message, senderForSend)
        if (supplemented.dateAdded || supplemented.messageIdAdded) {
            log.debug {
                "Supplemented outbound headers for $recipientForSend (dateAdded=${supplemented.dateAdded}, messageIdAdded=${supplemented.messageIdAdded})"
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
                    val classified = RelayFailureClassifier.classify(
                        e,
                        "Relay attempt failed (server=$targetServer port=$port mxPref=${mx.priority} msgId=${request.messageId})",
                    )
                    log.warn(e) {
                        "Relay attempt failed (server=$targetServer port=$port mxPref=${mx.priority} auth=${request.authenticated} msgId=${request.messageId} sender=${
                            senderForSend?.take(
                                64
                            ) ?: "null"
                        })"
                    }
                    if (!classified.isTransient) {
                        throw classified
                    }
                    lastException = classified
                }
            }
        }

        val last = lastException
        if (last != null) throw last
        throw RelayTransientException("Relay failed (domain=$normalizedDomain msgId=${request.messageId})")
    }

    private fun lookupMxRecords(domain: String): List<MxRecord> {
        val name = toDnsName(domain)

        val mxLookup = lookupRecords(name, Type.MX)
        if (mxLookup.records.isNotEmpty()) {
            val mxRecords = mxLookup.records.mapNotNull { it as? MXRecord }
            if (MxLookupPolicy.hasExplicitNullMx(mxRecords)) {
                throw RelayPermanentException("550 5.1.10 Null MX: domain does not accept email ($domain)")
            }

            return mxRecords
                .map { MxRecord(priority = it.priority, host = it.target.toString().removeSuffix(".")) }
                .filter { it.host.isNotBlank() }
                .sortedBy { it.priority }
        }

        when (mxLookup.resultCode) {
            Lookup.HOST_NOT_FOUND -> throw RelayPermanentException("550 5.1.2 Domain not found: $domain")
            Lookup.TRY_AGAIN -> throw RelayTransientException("451 4.4.3 DNS temporary failure while resolving MX: $domain")
            Lookup.UNRECOVERABLE -> throw RelayTransientException("451 4.4.3 DNS unrecoverable failure while resolving MX: $domain")
            Lookup.TYPE_NOT_FOUND, Lookup.SUCCESSFUL -> Unit
        }

        val aLookup = lookupRecords(name, Type.A)
        val aaaaLookup = lookupRecords(name, Type.AAAA)
        val hasA = aLookup.records.any { it is ARecord }
        val hasAAAA = aaaaLookup.records.any { it is AAAARecord }
        if (hasA || hasAAAA) return listOf(MxRecord(priority = 0, host = domain))

        val addressResultCodes = listOf(aLookup.resultCode, aaaaLookup.resultCode)
        return when {
            addressResultCodes.any { it == Lookup.HOST_NOT_FOUND } ->
                throw RelayPermanentException("550 5.1.2 Domain not found: $domain")

            addressResultCodes.any { it == Lookup.TRY_AGAIN || it == Lookup.UNRECOVERABLE } ->
                throw RelayTransientException("451 4.4.3 DNS temporary failure while resolving address records: $domain")

            else -> emptyList()
        }
    }

    private fun toDnsName(domain: String): Name {
        val d = domain.trim().removeSuffix(".")
        return Name.fromString("$d.")
    }

    private data class LookupResult(
        val records: List<Record>,
        val resultCode: Int,
    )

    private fun lookupRecords(name: Name, type: Int): LookupResult {
        val lookup = org.xbill.DNS.Lookup(name, type)
        lookup.setCache(dnsCache)
        val records = (lookup.run() ?: emptyArray()).toList()
        return LookupResult(records = records, resultCode = lookup.result)
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
