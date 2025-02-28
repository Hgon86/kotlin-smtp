package com.crinity.kotlinsmtp.mail

import com.crinity.kotlinsmtp.model.MxRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.xbill.DNS.Lookup
import org.xbill.DNS.MXRecord
import org.xbill.DNS.Type
import java.util.*

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
    private val dispatcherIO: CoroutineDispatcher
) {
    // TODO: Redis 캐싱으로 대체 예정
    // private val redisTemplate: RedisTemplate<String, List<MxRecord>>

    suspend fun relayToExternalServer(recipient: String, message: MimeMessage) = withContext(dispatcherIO) {
        val domain = recipient.substringAfterLast('@')
        val mxRecords = lookupMxRecords(domain)

        if (mxRecords.isEmpty()) {
            log.warn { "No MX records found for domain: $domain" }
            return@withContext
        }

        // MX 레코드 중 우선순위가 가장 높은 서버 선택
        val targetServer = mxRecords.minByOrNull { it.priority }?.host
            ?: throw IllegalStateException("No valid MX record found for $domain")

        log.info { "Relaying message to $recipient via $targetServer" }

        // 여러 포트 시도 (587, 465, 25)
        val ports = listOf(587, 465, 25)
        var lastException: Exception? = null

        for (port in ports) {
            try {
                val props = Properties().apply {
                    this["mail.smtp.host"] = targetServer
                    this["mail.smtp.port"] = port.toString()

                    // 포트별 설정
                    when (port) {
                        465 -> {
                            this["mail.smtp.ssl.enable"] = "true"
                            this["mail.smtp.ssl.trust"] = "*"
                        }

                        587 -> {
                            this["mail.smtp.starttls.enable"] = "true"
                            this["mail.smtp.starttls.required"] = "true"
                        }
                    }
                }

                val session = Session.getInstance(props)
                session.getTransport("smtp").use { transport ->
                    log.info { "Attempting to connect to $targetServer on port $port" }
                    transport.connect()
                    transport.sendMessage(message, arrayOf(InternetAddress(recipient)))
                    log.info { "Successfully relayed message to $recipient via $targetServer:$port" }
                    return@withContext
                }
            } catch (e: Exception) {
                log.warn { "Failed to relay via port $port: ${e.message}" }
                lastException = e
            }
        }

        if (lastException != null) {
            throw lastException
        }
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
        // TODO: Redis 캐싱 구현
        // val cacheKey = "mx:domain:$domain"
        // val cachedRecords = redisTemplate.opsForValue().get(cacheKey)
        // if (cachedRecords != null) return cachedRecords

        return try {
            val records = Lookup(domain, Type.MX).run()?.mapNotNull { record ->
                (record as? MXRecord)?.let {
                    MxRecord(
                        priority = it.priority,
                        host = it.target.toString().removeSuffix(".")
                    )
                }
            }?.sortedBy { it.priority } ?: emptyList()

            // TODO: Redis에 결과 저장
            // redisTemplate.opsForValue().set(cacheKey, records, mxCacheTtlMillis, TimeUnit.MILLISECONDS)

            records
        } catch (e: Exception) {
            log.error(e) { "Error looking up MX records for $domain" }
            emptyList()
        }
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
        log.info { "Cache clearing requested (will be implemented with Redis)" }
    }
} 