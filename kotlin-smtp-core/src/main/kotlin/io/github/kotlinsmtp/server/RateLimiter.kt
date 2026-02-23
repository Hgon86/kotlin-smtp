package io.github.kotlinsmtp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * IP-based rate limiter
 * Limits connection count and message count to prevent spam/DoS attacks
 */
internal class RateLimiter(
    private val maxConnectionsPerIp: Int = 10,
    private val maxMessagesPerIpPerHour: Int = 100
) : SmtpRateLimiter {
    // Current connection count per IP
    private val connectionCounts = ConcurrentHashMap<String, AtomicInteger>()
    
    // Hourly message count per IP (timestamps)
    private val messageCounts = ConcurrentHashMap<String, MutableList<Long>>()
    
    /**
     * Check whether connection is allowed for IP
     * @return true if allowed, false if rejected
     */
    override fun allowConnection(ipAddress: String): Boolean {
        val count = connectionCounts.computeIfAbsent(ipAddress) { AtomicInteger(0) }
        val currentCount = count.incrementAndGet()
        
        if (currentCount > maxConnectionsPerIp) {
            count.decrementAndGet() // Rollback
            log.warn { "Rate limit: IP $ipAddress exceeded connection limit ($currentCount > $maxConnectionsPerIp)" }
            return false
        }
        
        log.debug { "Rate limit: IP $ipAddress connection allowed ($currentCount/$maxConnectionsPerIp)" }
        return true
    }
    
    /**
     * Decrease count on connection close
     */
    override fun releaseConnection(ipAddress: String) {
        connectionCounts[ipAddress]?.decrementAndGet()?.let { newCount ->
            log.debug { "Rate limit: IP $ipAddress connection released (remaining: $newCount)" }
            // Remove from map when count reaches 0 (prevent memory leak)
            if (newCount <= 0) {
                connectionCounts.remove(ipAddress)
            }
        }
    }
    
    /**
     * Check whether message sending is allowed
     * @return true if allowed, false if rejected
     */
    override fun allowMessage(ipAddress: String): Boolean {
        val now = Instant.now().epochSecond
        val oneHourAgo = now - 3600
        
        val timestamps = messageCounts.computeIfAbsent(ipAddress) { mutableListOf() }
        
        synchronized(timestamps) {
            // Remove records older than one hour
            timestamps.removeIf { it < oneHourAgo }
            
            if (timestamps.size >= maxMessagesPerIpPerHour) {
                log.warn { "Rate limit: IP $ipAddress exceeded message limit (${timestamps.size} >= $maxMessagesPerIpPerHour)" }
                return false
            }
            
            // Record new message
            timestamps.add(now)
            log.debug { "Rate limit: IP $ipAddress message allowed (${timestamps.size}/$maxMessagesPerIpPerHour per hour)" }
        }
        
        return true
    }
    
    /**
     * Periodic cleanup (prevent memory leaks)
     * Remove IPs with no activity for over one hour
     */
    override fun cleanup() {
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
