package io.github.kotlinsmtp.relay.jakarta

import io.github.kotlinsmtp.relay.api.OutboundRelayPolicy
import io.github.kotlinsmtp.relay.api.OutboundRelayPolicyResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.xbill.DNS.Cache
import org.xbill.DNS.DClass
import org.xbill.DNS.Lookup
import org.xbill.DNS.Record
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

private val policyLog = KotlinLogging.logger {}

/**
 * Default resolver for outbound relay security policies.
 *
 * Supports:
 * - MTA-STS TXT discovery + policy fetch (mode=enforce)
 * - Basic DANE signal check via TLSA lookup (`_25._tcp.<domain>`)
 */
public class StandardOutboundRelayPolicyResolver(
    private val mtaStsEnabled: Boolean = false,
    private val daneEnabled: Boolean = false,
    private val mtaStsConnectTimeoutMs: Int = 3_000,
    private val mtaStsReadTimeoutMs: Int = 5_000,
    private val defaultPolicyCacheTtlSeconds: Long = 3_600,
    private val maxCacheEntries: Int = 10_000,
    private val dnsLookup: OutboundPolicyDnsLookup = DnsjavaOutboundPolicyDnsLookup(),
    private val mtaStsFetcher: MtaStsPolicyFetcher = HttpMtaStsPolicyFetcher(
        connectTimeoutMs = mtaStsConnectTimeoutMs,
        readTimeoutMs = mtaStsReadTimeoutMs,
    ),
) : OutboundRelayPolicyResolver {
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CachedPolicy>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPolicy>?): Boolean {
            return size > maxCacheEntries
        }
    }

    private data class CachedPolicy(
        val policy: OutboundRelayPolicy?,
        val expiresAtEpochSeconds: Long,
    )
    /**
     * Resolves merged outbound policy for recipient domain.
     *
     * @param recipientDomain normalized recipient domain
     * @return merged outbound policy, or null when no effective policy exists
     */
    override fun resolve(recipientDomain: String): OutboundRelayPolicy? {
        val normalized = recipientDomain.trim().lowercase()
        if (normalized.isBlank()) return null

        val now = Instant.now().epochSecond
        val cached = getCachedPolicy(normalized, now)
        if (cached != null && cached.expiresAtEpochSeconds > now) {
            return cached.policy
        }

        val resolved = resolveInternal(normalized)
        val ttlSeconds = resolved?.cacheTtlSeconds ?: defaultPolicyCacheTtlSeconds
        putCachedPolicy(
            domain = normalized,
            cachedPolicy = CachedPolicy(
                policy = resolved?.policy,
                expiresAtEpochSeconds = now + ttlSeconds.coerceAtLeast(60),
            ),
        )
        return resolved?.policy
    }

    private fun getCachedPolicy(domain: String, nowEpochSeconds: Long): CachedPolicy? = synchronized(cacheLock) {
        val cached = cache[domain] ?: return@synchronized null
        if (cached.expiresAtEpochSeconds <= nowEpochSeconds) {
            cache.remove(domain)
            return@synchronized null
        }
        cached
    }

    private fun putCachedPolicy(domain: String, cachedPolicy: CachedPolicy) = synchronized(cacheLock) {
        cache[domain] = cachedPolicy
    }

    private data class ResolvedPolicy(
        val policy: OutboundRelayPolicy?,
        val cacheTtlSeconds: Long,
    )

    private fun resolveInternal(domain: String): ResolvedPolicy? {
        val mtaSts = resolveMtaStsPolicy(domain)
        val dane = resolveDanePolicy(domain)
        val policies = listOfNotNull(mtaSts?.policy, dane)
        if (policies.isEmpty()) return null

        val merged = OutboundRelayPolicy(
            requireTls = policies.any { it.requireTls },
            requireValidCertificate = policies.any { it.requireValidCertificate },
            source = policies.mapNotNull { it.source }.joinToString("+").ifBlank { null },
        )
        val ttl = mtaSts?.cacheTtlSeconds ?: defaultPolicyCacheTtlSeconds
        return ResolvedPolicy(policy = merged, cacheTtlSeconds = ttl)
    }

    private data class MtaStsResolved(
        val policy: OutboundRelayPolicy,
        val cacheTtlSeconds: Long,
    )

    private fun resolveMtaStsPolicy(domain: String): MtaStsResolved? {
        if (!mtaStsEnabled) return null

        val stsTxtRecords = runCatching { dnsLookup.lookupTxt("_mta-sts.$domain") }
            .onFailure { e -> policyLog.debug(e) { "MTA-STS TXT lookup failed for $domain" } }
            .getOrDefault(emptyList())
        if (stsTxtRecords.none { it.contains("v=STSv1", ignoreCase = true) }) return null

        val policyText = runCatching { mtaStsFetcher.fetchPolicy(domain) }
            .onFailure { e -> policyLog.debug(e) { "MTA-STS policy fetch failed for $domain" } }
            .getOrNull()
            ?: return null

        val parsed = parseMtaStsPolicy(policyText)
        if (!parsed.version.equals("STSv1", ignoreCase = true)) return null
        val maxAge = parsed.maxAge ?: defaultPolicyCacheTtlSeconds

        return when (parsed.mode?.lowercase()) {
            "enforce" -> MtaStsResolved(
                policy = OutboundRelayPolicy(
                    requireTls = true,
                    requireValidCertificate = true,
                    source = "MTA-STS(enforce)",
                ),
                cacheTtlSeconds = maxAge,
            )

            "testing" -> MtaStsResolved(
                policy = OutboundRelayPolicy(
                    requireTls = false,
                    requireValidCertificate = false,
                    source = "MTA-STS(testing)",
                ),
                cacheTtlSeconds = maxAge,
            )

            "none" -> null
            else -> null
        }
    }

    private fun resolveDanePolicy(domain: String): OutboundRelayPolicy? {
        if (!daneEnabled) return null
        val hasTlsa = runCatching { dnsLookup.hasTlsa("_25._tcp.$domain") }
            .onFailure { e -> policyLog.debug(e) { "DANE TLSA lookup failed for $domain" } }
            .getOrDefault(false)
        if (!hasTlsa) return null

        return OutboundRelayPolicy(
            requireTls = true,
            requireValidCertificate = true,
            source = "DANE(basic)",
        )
    }

    private data class ParsedMtaStsPolicy(
        val version: String?,
        val mode: String?,
        val maxAge: Long?,
    )

    private fun parseMtaStsPolicy(raw: String): ParsedMtaStsPolicy {
        val kv = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                key to value
            }
            .toMap()

        return ParsedMtaStsPolicy(
            version = kv["version"],
            mode = kv["mode"],
            maxAge = kv["max_age"]?.toLongOrNull(),
        )
    }
}

/**
 * DNS lookup adapter used by outbound policy resolver.
 */
public interface OutboundPolicyDnsLookup {
    /**
     * Reads TXT records for DNS name.
     *
     * @param dnsName fully-qualified DNS name without trailing dot requirement
     * @return TXT record values
     */
    public fun lookupTxt(dnsName: String): List<String>

    /**
     * Checks whether TLSA records exist for DNS name.
     *
     * @param dnsName fully-qualified DNS name without trailing dot requirement
     * @return true when at least one TLSA record exists
     */
    public fun hasTlsa(dnsName: String): Boolean
}

/**
 * dnsjava-backed DNS lookup implementation for outbound policies.
 */
public class DnsjavaOutboundPolicyDnsLookup(
    private val dnsCache: Cache = Cache(DClass.IN).apply { setMaxEntries(10_000) },
) : OutboundPolicyDnsLookup {
    override fun lookupTxt(dnsName: String): List<String> {
        val records = runLookup(dnsName, Type.TXT)
        return records
            .mapNotNull { it as? TXTRecord }
            .map { txt -> txt.strings.joinToString("") }
    }

    override fun hasTlsa(dnsName: String): Boolean = runLookup(dnsName, Type.TLSA).isNotEmpty()

    private fun runLookup(dnsName: String, type: Int): List<Record> {
        val fqdn = dnsName.trim().removeSuffix(".") + "."
        val lookup = Lookup(fqdn, type)
        lookup.setCache(dnsCache)
        return (lookup.run() ?: emptyArray()).toList()
    }
}

/**
 * HTTP fetcher used to retrieve MTA-STS policy text.
 */
public fun interface MtaStsPolicyFetcher {
    /**
     * Fetches policy text for a domain.
     *
     * @param domain recipient domain
     * @return raw MTA-STS policy text, or null when unavailable
     */
    public fun fetchPolicy(domain: String): String?
}

/**
 * Default HTTP fetcher for MTA-STS policy.
 */
public class HttpMtaStsPolicyFetcher(
    connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
) : MtaStsPolicyFetcher {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs.toLong()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetchPolicy(domain: String): String? {
        val uri = URI.create("https://mta-sts.$domain/.well-known/mta-sts.txt")
        val request = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(Duration.ofMillis(readTimeoutMs.toLong()))
            .header("User-Agent", "kotlin-smtp/relay")
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        return response.body()
    }
}
