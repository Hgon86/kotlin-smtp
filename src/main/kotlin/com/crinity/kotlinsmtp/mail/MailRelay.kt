package com.crinity.kotlinsmtp.mail

import com.crinity.kotlinsmtp.model.MxRecord
import com.crinity.kotlinsmtp.config.SmtpServerConfig
import com.crinity.kotlinsmtp.utils.AddressUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.Cache
import org.xbill.DNS.DClass
import org.xbill.DNS.MXRecord
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Type
import java.time.Duration
import java.util.Properties

private val log = KotlinLogging.logger {}

/**
 * 외부 메일 서버로 메일을 릴레이하는 클래스
 *
 * 참고: 현재는 로컬 캐싱을 사용하지만, 추후 Redis로 대체 예정
 * Redis 구현 시 다음 기능이 필요합니다:
 * 1. MX 레코드 캐싱 (도메인 -> MX 레코드 리스트)
 * 2. TTL 기반 만료 처리
 * 3. 분산 환경에서 캐시 공유
 */
class MailRelay(
    private val dispatcherIO: CoroutineDispatcher,
    private val tls: SmtpServerConfig.OutboundTlsConfig,
) {
    // TODO: Redis 캐싱으로 대체 예정
    // private val redisTemplate: RedisTemplate<String, List<MxRecord>>

    // dnsjava 캐시(메모리) 기반 룩업 세션
    // - 기능 우선: 일단 단일 노드에서 DNS 조회 비용을 줄이는 목적
    // TODO(msa/storage): 멀티 인스턴스 환경이면 Redis 등 분산 캐시로 이관 고려
    private val dnsCache = Cache(DClass.IN).apply {
        setMaxEntries(10_000)
        // dnsjava(Cache) 버전에 따라 TTL 설정 API가 다릅니다.
        // 기능 우선: 컴파일/동작을 우선하고, TTL 정책은 추후 캐시 레이어(예: Redis)로 이관합니다.
    }

    suspend fun relayMessage(
        sender: String?,
        recipient: String,
        message: MimeMessage,
        messageId: String,
        authenticated: Boolean
    ) = withContext(dispatcherIO) {
        // SMTPUTF8/IDN: Unicode 도메인은 DNS 조회 전에 IDNA(ASCII)로 정규화해야 합니다.
        // 또한 일부 라이브러리/서버는 Unicode 도메인을 주소 파싱에서 거부할 수 있으므로,
        // 아웃바운드에서는 domain만 punycode로 정규화한 주소를 사용합니다.
        val recipientForSend = AddressUtils.normalizeDomainInAddress(recipient)
        val senderForSend = sender?.takeIf { it.isNotBlank() }?.let { AddressUtils.normalizeDomainInAddress(it) }

        val rawDomain = recipientForSend.substringAfterLast('@')
        // SMTPUTF8/IDN: Unicode 도메인은 DNS 조회 전에 IDNA(ASCII)로 정규화해야 합니다.
        val normalizedDomain = AddressUtils.normalizeDomain(rawDomain) ?: rawDomain
        val mxRecords = lookupMxRecords(normalizedDomain)

        if (mxRecords.isEmpty()) {
            log.warn { "No MX records found for domain: $normalizedDomain" }
            throw IllegalStateException("No MX records for domain: $normalizedDomain (msgId=$messageId)")
        }

        // 운영 기본은 25(MTA-to-MTA). 개발환경 편의로 설정에서만 확장.
        val ports = tls.ports.ifEmpty { listOf(25) }
        var lastException: Exception? = null

        // RFC 관례: 우선순위 오름차순으로 MX를 순회하며 시도합니다.
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
                        // SMTP 엔벌로프(리턴-패스) 설정: From 헤더와 별개로 MAIL FROM에 반영됩니다.
                        // - DSN 등에서 sender가 null/blank일 수 있으므로 빈 reverse-path를 허용합니다.
                        this["mail.smtp.from"] = (senderForSend ?: "").trim()

                        // SMTPUTF8/UTF-8 헤더 지원(최소 구현)
                        // TODO: 구현체 버전별 공식 문서 확인 후 고정
                        this["mail.mime.allowutf8"] = "true"
                        this["mail.smtp.allowutf8"] = "true"

                        // STARTTLS 정책 (기본: opportunistic)
                        this["mail.smtp.starttls.enable"] = tls.startTlsEnabled.toString()
                        this["mail.smtp.starttls.required"] = tls.startTlsRequired.toString()

                        // TLS 인증서/호스트 검증 정책
                        // - 운영: 기본 JVM trust store + checkServerIdentity=true 권장
                        // - 개발: trustAll=true 또는 trustHosts로 완화 가능
                        this["mail.smtp.ssl.checkserveridentity"] = tls.checkServerIdentity.toString()
                        when {
                            tls.trustAll -> this["mail.smtp.ssl.trust"] = "*"
                            tls.trustHosts.isNotEmpty() -> this["mail.smtp.ssl.trust"] = tls.trustHosts.joinToString(" ")
                        }
                    }

                    val session = Session.getInstance(props)
                    session.getTransport("smtp").use { transport ->
                        log.info { "Attempting to connect to $targetServer on port $port (mxPref=${mx.priority})" }
                        transport.connect()
                        // SMTPUTF8 주소는 InternetAddress 파서가 거부할 수 있어, 엄격 검증 없이 address만 세팅합니다.
                        val rcptAddr = InternetAddress().apply { address = recipientForSend }
                        transport.sendMessage(message, arrayOf(rcptAddr))
                        log.info { "Successfully relayed message to $recipientForSend via $targetServer:$port (mxPref=${mx.priority})" }
                        return@withContext
                    }
                } catch (e: Exception) {
                    // 메일 내용/주소 등 민감정보는 최소화해서 기록합니다.
                    log.warn(e) {
                        "Relay attempt failed (server=$targetServer port=$port mxPref=${mx.priority} auth=$authenticated msgId=$messageId sender=${senderForSend?.take(64) ?: "null"})"
                    }
                    lastException = e
                }
            }
        }

        throw lastException ?: IllegalStateException("Relay failed (domain=$normalizedDomain msgId=$messageId)")
    }

    /**
     * 도메인에 대한 MX 레코드를 조회합니다.
     *
     * TODO: Redis 캐싱 구현 예정
     * 1. Redis에서 먼저 조회 (KEY: "mx:domain:{domain}")
     * 2. 없으면 DNS 조회 후 Redis에 저장 (TTL 설정)
     * 3. 분산 환경에서 동시성 고려
     */
    private fun lookupMxRecords(domain: String): List<MxRecord> {
        // 기능 우선: MX를 조회하고, 없으면 A/AAAA가 있는 경우 "implicit MX(0)"로 도메인 자체를 사용합니다.
        // (RFC 5321 관례)
        return runCatching {
            val name = toDnsName(domain)
            val mx = lookupRecords(name, Type.MX).mapNotNull { it as? MXRecord }.map {
                MxRecord(priority = it.priority, host = it.target.toString().removeSuffix("."))
            }.sortedBy { it.priority }

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
        // dnsjava Lookup 사용(캐시 적용)
        val lookup = org.xbill.DNS.Lookup(name, type)
        lookup.setCache(dnsCache)
        return (lookup.run() ?: emptyArray()).toList()
    }

    /**
     * MX 레코드 캐시를 비웁니다.
     *
     * TODO: Redis 캐시 삭제 구현
     * 1. 특정 도메인 캐시만 삭제 기능
     * 2. 전체 MX 캐시 삭제 기능
     * 3. 패턴 기반 삭제 (예: "mx:domain:gmail.*")
     */
    fun clearCache() {
        // TODO: Redis 캐시 삭제 구현
        // redisTemplate.delete("mx:domain:*")
        // dnsjava 메모리 캐시를 초기화합니다(운영 시 관리자 기능으로만 노출 권장)
        dnsCache.clearCache()
        log.info { "Cache clearing requested (will be implemented with Redis)" }
    }
}
