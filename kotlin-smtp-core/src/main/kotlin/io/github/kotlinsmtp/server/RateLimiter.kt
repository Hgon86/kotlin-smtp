package io.github.kotlinsmtp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * IP 기반 Rate Limiter
 * 스팸 및 DoS 공격 방지를 위한 연결 수 및 메시지 수 제한
 */
class RateLimiter(
    private val maxConnectionsPerIp: Int = 10,
    private val maxMessagesPerIpPerHour: Int = 100
) {
    // IP별 현재 연결 수
    private val connectionCounts = ConcurrentHashMap<String, AtomicInteger>()
    
    // IP별 시간당 메시지 수 (타임스탬프, 카운트)
    private val messageCounts = ConcurrentHashMap<String, MutableList<Long>>()
    
    /**
     * IP 주소에서 연결 허용 여부 확인
     * @return true면 허용, false면 거부
     */
    fun allowConnection(ipAddress: String): Boolean {
        val count = connectionCounts.computeIfAbsent(ipAddress) { AtomicInteger(0) }
        val currentCount = count.incrementAndGet()
        
        if (currentCount > maxConnectionsPerIp) {
            count.decrementAndGet() // 롤백
            log.warn { "Rate limit: IP $ipAddress exceeded connection limit ($currentCount > $maxConnectionsPerIp)" }
            return false
        }
        
        log.debug { "Rate limit: IP $ipAddress connection allowed ($currentCount/$maxConnectionsPerIp)" }
        return true
    }
    
    /**
     * 연결 종료 시 카운트 감소
     */
    fun releaseConnection(ipAddress: String) {
        connectionCounts[ipAddress]?.decrementAndGet()?.let { newCount ->
            log.debug { "Rate limit: IP $ipAddress connection released (remaining: $newCount)" }
            // 카운트가 0이 되면 맵에서 제거 (메모리 누수 방지)
            if (newCount <= 0) {
                connectionCounts.remove(ipAddress)
            }
        }
    }
    
    /**
     * 메시지 전송 허용 여부 확인
     * @return true면 허용, false면 거부
     */
    fun allowMessage(ipAddress: String): Boolean {
        val now = Instant.now().epochSecond
        val oneHourAgo = now - 3600
        
        val timestamps = messageCounts.computeIfAbsent(ipAddress) { mutableListOf() }
        
        synchronized(timestamps) {
            // 1시간 이전 기록 제거
            timestamps.removeIf { it < oneHourAgo }
            
            if (timestamps.size >= maxMessagesPerIpPerHour) {
                log.warn { "Rate limit: IP $ipAddress exceeded message limit (${timestamps.size} >= $maxMessagesPerIpPerHour)" }
                return false
            }
            
            // 새 메시지 기록
            timestamps.add(now)
            log.debug { "Rate limit: IP $ipAddress message allowed (${timestamps.size}/$maxMessagesPerIpPerHour per hour)" }
        }
        
        return true
    }
    
    /**
     * 주기적 정리 (메모리 누수 방지)
     * 1시간 이상 활동 없는 IP 제거
     */
    fun cleanup() {
        val now = Instant.now().epochSecond
        val oneHourAgo = now - 3600
        
        messageCounts.entries.removeIf { (ip, timestamps) ->
            synchronized(timestamps) {
                timestamps.removeIf { it < oneHourAgo }
                val shouldRemove = timestamps.isEmpty()
                if (shouldRemove) {
                    log.debug { "Rate limit: Cleaned up inactive IP $ip" }
                }
                shouldRemove
            }
        }
    }
}
