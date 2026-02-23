package io.github.kotlinsmtp.spool

import jakarta.annotation.PreDestroy
import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.metrics.SpoolMetrics
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.RelayException
import io.github.kotlinsmtp.server.SpoolTriggerResult
import io.github.kotlinsmtp.server.SmtpDomainSpooler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.net.ConnectException
import java.time.Duration
import java.time.Instant
import java.net.IDN
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/**
 * Result of a single-message delivery attempt.
 *
 * @property delivered recipients delivered successfully
 * @property transientFailures transient failure reasons by recipient
 * @property permanentFailures permanent failure reasons by recipient
 */
private data class DeliveryAttemptResult(
    val delivered: List<String>,
    val transientFailures: Map<String, String>,
    val permanentFailures: Map<String, String>,
)

/**
 * Manages mail delivery and retries via spooling.
 *
 * Depending on storage implementation, messages are stored in file or Redis,
 * and a background worker retries delivery at configured intervals.
 *
 * @param spoolDir spool directory path
 * @param maxRetries maximum retry count
 * @param retryDelaySeconds initial retry delay in seconds
 * @param workerConcurrency maximum concurrent message workers per spool run (run cycles stay serialized)
 * @param dispatcher coroutine dispatcher
 * @param deliveryService mail delivery service
 * @param dsnSenderProvider DSN sender provider
 * @param spoolMetrics spool metrics collector
 * @param metadataStore spool metadata store implementation
 * @param lockManager spool lock manager implementation
 * @param triggerCooldownMillis minimum interval between external trigger acceptances
 */
class MailSpooler(
    private val spoolDir: Path,
    private val maxRetries: Int = 5,
    private val retryDelaySeconds: Long = 60,
    private val workerConcurrency: Int = 1,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deliveryService: MailDeliveryService,
    private val dsnSenderProvider: () -> DsnSender?,
    private val spoolMetrics: SpoolMetrics = SpoolMetrics.NOOP,
    injectedMetadataStore: SpoolMetadataStore? = null,
    injectedLockManager: SpoolLockManager? = null,
    private val triggerCooldownMillis: Long = 1000L,
) : SmtpDomainSpooler {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
    private var worker: Job? = null
    private val staleLockThreshold = Duration.ofMinutes(15)
    private val runMutex = Mutex() // Prevent concurrent runs between triggerOnce() and worker loop.
    private val lockManager = injectedLockManager ?: FileSpoolLockManager(spoolDir, staleLockThreshold)
    private val failurePolicy = SpoolFailurePolicy()
    private val metadataStore = injectedMetadataStore ?: FileSpoolMetadataStore(spoolDir)
    private val triggerDispatcher = SpoolTriggerDispatcher(scope) { domain -> runOnce(domain) }
    private val lockRefreshIntervalMillis = 30_000L
    private val lastAcceptedTriggerAtMillis = AtomicLong(0L)

    init {
        require(workerConcurrency > 0) {
            "workerConcurrency must be > 0"
        }
        metadataStore.initializeDirectory()
        spoolMetrics.initializePending(metadataStore.scanPendingMessageCount())
        start()
    }

    /**
     * Performs one immediate spool processing run via external trigger.
     *
     * - Feature-first: useful for ops/admin scenarios (for example, ETRN).
     * - Concurrency control: serialize in-process runs with a mutex.
     * - Applies minimal cooldown to prevent excessive admin-trigger abuse.
     *
     * @return trigger acceptance result
     */
    override fun tryTriggerOnce(): SpoolTriggerResult {
        if (!acquireTriggerQuota()) {
            return SpoolTriggerResult.UNAVAILABLE
        }
        triggerDispatcher.submit(targetDomain = null)
        return SpoolTriggerResult.ACCEPTED
    }

    override fun triggerOnce() {
        tryTriggerOnce()
    }

    /**
     * Performs one immediate spool run for a specific domain only.
     *
     * @param domain domain passed via ETRN argument
     * @return trigger acceptance result
     */
    override fun tryTriggerOnce(domain: String): SpoolTriggerResult {
        val normalized = normalizeDomain(domain)
        if (normalized == null) {
            log.warn { "Spooler triggerOnce(domain) skipped: invalid domain=$domain" }
            return SpoolTriggerResult.INVALID_ARGUMENT
        }

        if (!acquireTriggerQuota()) {
            log.warn { "Spooler triggerOnce(domain) throttled: domain=$normalized" }
            return SpoolTriggerResult.UNAVAILABLE
        }

        triggerDispatcher.submit(targetDomain = normalized)
        return SpoolTriggerResult.ACCEPTED
    }

    override fun triggerOnce(domain: String) {
        tryTriggerOnce(domain)
    }

    /**
     * Enqueues a message into spool storage.
     *
     * @param rawMessagePath original message file path
     * @param sender envelope sender
     * @param recipients recipient list
     * @param messageId message identifier
     * @param authenticated whether message is authenticated
     * @param peerAddress client address
     * @param dsnRet DSN RET option
     * @param dsnEnvid DSN ENVID option
     * @param rcptDsn per-recipient DSN options
     * @return created spool metadata
     */
    fun enqueue(
        rawMessagePath: Path,
        sender: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
        peerAddress: String? = null,
        dsnRet: String? = null,
        dsnEnvid: String? = null,
        rcptDsn: Map<String, RcptDsn> = emptyMap(),
    ): SpoolMetadata {
        val metadata = metadataStore.createMessage(
            rawMessagePath = rawMessagePath,
            sender = sender,
            recipients = recipients,
            messageId = messageId,
            authenticated = authenticated,
            peerAddress = peerAddress,
            dsnRet = dsnRet,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn,
        )
        spoolMetrics.onQueued()
        log.info { "Spool queued: ${metadata.rawPath} (recipients=${recipients.size})" }
        return metadata
    }

    /**
     * Starts the background worker.
     *
     * Repeatedly processes the spool queue with retry interval.
     */
    private fun start() {
        if (worker != null) return
        worker = scope.launch {
            while (true) {
                try {
                    runOnce()
                    delay(retryDelaySeconds * 1000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Spooler worker loop error" }
                    delay(retryDelaySeconds * 1000)
                }
            }
        }
    }

    /**
     * Stops the spooler worker and releases resources.
     */
    @PreDestroy
    fun shutdown() {
        worker?.cancel()
        worker = null
        scope.cancel("MailSpooler shutdown")
    }

    /**
     * Processes the spool queue once.
     *
     * Serializes in-process execution with mutex to avoid duplicate processing.
     *
     * @param targetDomain specific domain to process; null for full queue
     */
    private suspend fun runOnce(targetDomain: String? = null) {
        // Feature-first: file locks (.lock) mostly prevent duplicates on a single node,
        // but consecutive triggerOnce() calls or overlap with worker loop can cause
        // unnecessary scans/lock attempts, so serialize runs with in-process mutex.
        runMutex.withLock {
            processQueueOnce(targetDomain)
            lockManager.purgeOrphanedLocks()
        }
    }

    /**
     * Calculates retry delay.
     *
     * Applies exponential backoff with jitter, capped at 10 minutes.
     *
     * @param attempt current attempt number
     * @return wait time in seconds until next retry
     */
    private fun nextBackoffSeconds(attempt: Int): Long {
        val base = retryDelaySeconds.toDouble()
        val exp = base * (1 shl (attempt - 1))
        val bounded = min(600.0, exp)
        val jitterFactor = 0.8 + Random.nextDouble() * 0.4
        return (bounded * jitterFactor).toLong().coerceAtLeast(retryDelaySeconds)
    }

    /**
     * Processes all messages in spool directory once.
     *
     * @param targetDomain specific domain to process; null for full queue
     */
    private suspend fun processQueueOnce(targetDomain: String? = null) {
        val now = Instant.now()
        val files = metadataStore.listDueMessages(now)
        if (files.isEmpty()) return

        val parallelism = workerConcurrency.coerceAtLeast(1)
        if (parallelism == 1 || files.size == 1) {
            for (file in files) {
                if (!lockManager.tryLock(file)) continue
                try {
                    processSingleMessage(file, targetDomain)
                } finally {
                    lockManager.unlock(file)
                }
            }
            return
        }

        val semaphore = Semaphore(parallelism)
        coroutineScope {
            files.map { file ->
                async {
                    semaphore.withPermit {
                        if (!lockManager.tryLock(file)) return@withPermit
                        try {
                            processSingleMessage(file, targetDomain)
                        } finally {
                            lockManager.unlock(file)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Processes a single spool message.
     *
     * Performs metadata load -> recipient delivery -> result classification -> DSN/retry handling.
     *
     * @param file spool message file path
     * @param targetDomain specific domain to process; null for all recipients
     */
    private suspend fun processSingleMessage(file: Path, targetDomain: String?) {
        val meta = metadataStore.readMeta(file) ?: return
        // Keep this guard for race safety (clock skew/stale queue state/manual writes).
        if (meta.nextAttemptAt.isAfter(Instant.now())) return

        val deliveryRawPath = runCatching { metadataStore.prepareRawMessageForDelivery(file) }
            .getOrElse { throwable ->
                handlePreparationFailure(file, meta, throwable)
                return
            }
        try {
            withLockHeartbeat(file) {
                val recipientsToProcess = recipientsToProcess(meta.recipients, targetDomain)
                if (recipientsToProcess.isEmpty()) return@withLockHeartbeat
                val attemptedAllRecipients = recipientsToProcess.size == meta.recipients.size

                val deliveryResult = deliverRecipients(meta, recipientsToProcess, deliveryRawPath)
                spoolMetrics.onDeliveryResults(
                    deliveredCount = deliveryResult.delivered.size,
                    transientFailureCount = deliveryResult.transientFailures.size,
                    permanentFailureCount = deliveryResult.permanentFailures.size,
                )

                val rcptDsnSnapshot = meta.rcptDsn.toMap()
                val permanentDsnTargets = failurePolicy.selectFailureDsnTargets(deliveryResult.permanentFailures, rcptDsnSnapshot)

                updateRecipientsAfterDelivery(meta, deliveryResult.delivered, deliveryResult.permanentFailures.keys)
                sendPermanentFailureDsn(meta, deliveryRawPath, permanentDsnTargets, rcptDsnSnapshot)

                if (meta.recipients.isEmpty()) {
                    completeMessage(file, meta)
                    return@withLockHeartbeat
                }

                handleTransientFailures(
                    meta = meta,
                    spoolReferencePath = file,
                    deliveryRawPath = deliveryRawPath,
                    transientFailures = deliveryResult.transientFailures,
                    attemptedAllRecipients = attemptedAllRecipients,
                    targetDomain = targetDomain,
                )
            }
        } finally {
            metadataStore.cleanupPreparedRawMessage(deliveryRawPath)
        }
    }

    /**
     * Executes message processing block while maintaining lock heartbeat.
     *
     * @param spoolReferencePath spool message reference path
     * @param block actual processing block
     */
    private suspend fun withLockHeartbeat(spoolReferencePath: Path, block: suspend () -> Unit) {
        coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    delay(lockRefreshIntervalMillis)
                    val refreshed = runCatching { lockManager.refreshLock(spoolReferencePath) }.getOrDefault(false)
                    if (!refreshed) {
                        throw IllegalStateException("Failed to refresh spool lock: $spoolReferencePath")
                    }
                }
            }
            try {
                block()
            } finally {
                heartbeat.cancelAndJoin()
            }
        }
    }

    /**
     * Classifies preparation-stage failures and applies follow-up handling.
     *
     * @param spoolReferencePath spool message reference path
     * @param meta spool metadata
     * @param throwable raised exception
     */
    private fun handlePreparationFailure(spoolReferencePath: Path, meta: SpoolMetadata, throwable: Throwable) {
        if (throwable is SpoolCorruptedMessageException) {
            log.warn(throwable) { "Dropping corrupted spool message: $spoolReferencePath (id=${meta.id})" }
            metadataStore.removeMessage(spoolReferencePath)
            spoolMetrics.onDropped()
            return
        }
        throw throwable
    }

    /**
     * Removes a fully processed message from spool.
     *
     * @param spoolReferencePath spool message reference path
     * @param meta spool metadata
     */
    private fun completeMessage(spoolReferencePath: Path, meta: SpoolMetadata) {
        metadataStore.removeMessage(spoolReferencePath)
        spoolMetrics.onCompleted()
        spoolMetrics.onFinalized(outcome = "completed", queueAgeSeconds = queueAgeSeconds(meta))
        log.info { "Spool completed and removed: $spoolReferencePath (id=${meta.id})" }
    }

    /**
     * Attempts delivery for the given recipient list.
     *
     * @param meta spool metadata
     * @param recipientsToProcess recipients to process
     * @param file message file path
     * @return delivery attempt result
     */
    private suspend fun deliverRecipients(
        meta: SpoolMetadata,
        recipientsToProcess: List<String>,
        file: Path,
    ): DeliveryAttemptResult {
        val delivered = mutableListOf<String>()
        val transientFailures = linkedMapOf<String, String>()
        val permanentFailures = linkedMapOf<String, String>()

        for (rcpt in recipientsToProcess) {
            val domain = rcpt.substringAfterLast('@')
            runCatching {
                if (deliveryService.isLocalDomain(domain)) deliveryService.deliverLocal(rcpt, file)
                else deliveryService.relayExternal(
                    envelopeSender = meta.sender,
                    recipient = rcpt,
                    rawPath = file,
                    messageId = meta.messageId,
                    authenticated = meta.authenticated,
                    peerAddress = meta.peerAddress,
                    // Spooler owns retries/final DSN, so do not generate DSN here.
                    generateDsnOnFailure = false,
                )
            }.onSuccess {
                delivered.add(rcpt)
            }.onFailure { t ->
                if (t is CancellationException) throw t
                val reason = t.message ?: t::class.simpleName.orEmpty()
                val permanent = failurePolicy.isPermanentFailure(t)
                if (permanent) permanentFailures[rcpt] = reason else transientFailures[rcpt] = reason
                spoolMetrics.onRecipientFailure(
                    domain = normalizeDomain(rcpt.substringAfterLast('@', "")) ?: "unknown",
                    permanent = permanent,
                    reasonClass = classifyFailureReason(t, reason),
                )
                log.warn(t) { "Spool delivery failed for rcpt=$rcpt (id=${meta.id}) permanent=$permanent" }
            }
        }

        return DeliveryAttemptResult(
            delivered = delivered,
            transientFailures = transientFailures,
            permanentFailures = permanentFailures,
        )
    }

    /**
     * Updates recipient list based on delivery results.
     *
     * Removes delivered/permanently failed recipients to prevent duplicate delivery.
     *
     * @param meta spool metadata
     * @param delivered successfully delivered recipients
     * @param permanentFailureRecipients recipients with permanent failures
     */
    private fun updateRecipientsAfterDelivery(
        meta: SpoolMetadata,
        delivered: List<String>,
        permanentFailureRecipients: Set<String>,
    ) {
        if (delivered.isEmpty() && permanentFailureRecipients.isEmpty()) return

        val deliveredSet = delivered.toSet()
        meta.recipients.removeAll(deliveredSet)
        meta.recipients.removeAll(permanentFailureRecipients)
        meta.rcptDsn.keys.removeAll(deliveredSet)
        meta.rcptDsn.keys.removeAll(permanentFailureRecipients)
        log.info {
            "Spool recipient update: id=${meta.id} delivered=${delivered.size} permanentFail=${permanentFailureRecipients.size} remaining=${meta.recipients.size}"
        }
    }

    /**
     * Sends DSN for permanently failed recipients.
     *
     * @param meta spool metadata
     * @param file message file path
     * @param dsnTargets recipient-to-reason map for DSN
     * @param rcptDsnSnapshot snapshot of DSN options
     */
    private fun sendPermanentFailureDsn(
        meta: SpoolMetadata,
        file: Path,
        dsnTargets: Map<String, String>,
        rcptDsnSnapshot: Map<String, RcptDsn>,
    ) {
        if (dsnTargets.isEmpty()) return
        dsnSenderProvider()?.sendPermanentFailure(
            sender = meta.sender,
            failedRecipients = dsnTargets.entries.map { it.key to it.value },
            originalMessageId = meta.messageId,
            originalMessagePath = file,
            dsnEnvid = meta.dsnEnvid,
            dsnRet = meta.dsnRet,
            rcptDsn = rcptDsnSnapshot.filterKeys { it in dsnTargets.keys },
        )
    }

    /**
     * Handles post-processing for transiently failed recipients.
     *
     * Decides retry scheduling or drop when max retries are exceeded.
     *
     * @param meta spool metadata
     * @param spoolReferencePath spool message reference path
     * @param deliveryRawPath raw file path used for delivery
     * @param transientFailures transient failure reasons by recipient
     * @param attemptedAllRecipients whether all recipients were attempted
     * @param targetDomain domain target for processing (ETRN)
     */
    private fun handleTransientFailures(
        meta: SpoolMetadata,
        spoolReferencePath: Path,
        deliveryRawPath: Path,
        transientFailures: Map<String, String>,
        attemptedAllRecipients: Boolean,
        targetDomain: String?,
    ) {
        if (transientFailures.isEmpty()) {
            // Defensive: persist metadata if recipients remain after permanent-failure handling.
            metadataStore.writeMeta(meta)
            log.info { "Spool state saved without retry: id=${meta.id} remainingRcpt=${meta.recipients.size}" }
            return
        }

        if (!attemptedAllRecipients) {
            // For partial runs such as domain-targeted ETRN,
            // increasing global attempt/backoff would penalize untouched recipients.
            // Persist state only and keep global retry counters unchanged.
            metadataStore.writeMeta(meta)
            log.info {
                "Spool partial run saved without retry increment: id=${meta.id} targetDomain=$targetDomain transientFail=${transientFailures.size} remainingRcpt=${meta.recipients.size}"
            }
            return
        }

        meta.attempt += 1
        if (meta.attempt >= maxRetries) {
            log.warn { "Spool drop after max retries: $spoolReferencePath (id=${meta.id})" }
            metadataStore.removeMessage(spoolReferencePath)
            spoolMetrics.onDropped()
            spoolMetrics.onFinalized(outcome = "dropped", queueAgeSeconds = queueAgeSeconds(meta))
            val dsnTargets = failurePolicy.selectFailureDsnTargets(transientFailures, meta.rcptDsn)
            val details = dsnTargets.entries.map { it.key to it.value }
            if (details.isNotEmpty()) {
                dsnSenderProvider()?.sendPermanentFailure(
                    sender = meta.sender,
                    failedRecipients = details,
                    originalMessageId = meta.messageId,
                    originalMessagePath = deliveryRawPath,
                    dsnEnvid = meta.dsnEnvid,
                    dsnRet = meta.dsnRet,
                    rcptDsn = meta.rcptDsn.filterKeys { it in dsnTargets.keys },
                )
            }
            return
        }

        val backoff = nextBackoffSeconds(meta.attempt)
        meta.nextAttemptAt = Instant.now().plusSeconds(backoff)
        metadataStore.writeMeta(meta)
        spoolMetrics.onRetryScheduled(backoff)
        log.info {
            "Spool retry scheduled: id=${meta.id} attempt=${meta.attempt} remainingRcpt=${meta.recipients.size} next=${meta.nextAttemptAt} (backoff=${backoff}s)"
        }
    }

    /**
     * Classifies delivery failure reason into coarse operational categories.
     */
    private fun classifyFailureReason(throwable: Throwable, reason: String): String {
        when (throwable) {
            is UnknownHostException -> return "dns"
            is SocketTimeoutException -> return "timeout"
            is ConnectException -> return "network"
            is SSLException -> return "tls"
            is RelayException -> if (!throwable.isTransient) return "policy"
        }

        val value = reason.lowercase()
        return when {
            "dns" in value || "mx" in value || "host" in value -> "dns"
            "tls" in value || "ssl" in value || "certificate" in value -> "tls"
            "timeout" in value || "timed out" in value -> "timeout"
            "relay" in value || "policy" in value || "denied" in value || "auth" in value -> "policy"
            "connect" in value || "socket" in value || "network" in value -> "network"
            else -> "other"
        }
    }

    /**
     * Computes queue residence time in seconds.
     */
    private fun queueAgeSeconds(meta: SpoolMetadata): Long {
        val seconds = Duration.between(meta.queuedAt, Instant.now()).seconds
        return seconds.coerceAtLeast(0)
    }

    /**
     * Normalizes domain to IDNA ASCII lowercase.
     *
     * @param domain domain to normalize
     * @return normalized domain, or null if invalid
     */
    private fun normalizeDomain(domain: String): String? = runCatching {
        val trimmed = domain.trim().trimEnd('.')
        if (trimmed.isEmpty()) return null
        IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).lowercase()
    }.getOrNull()

    /**
     * Applies a simple wall-clock cooldown for external spool triggers.
     */
    private fun acquireTriggerQuota(nowMillis: Long = System.currentTimeMillis()): Boolean {
        while (true) {
            val last = lastAcceptedTriggerAtMillis.get()
            if (nowMillis - last < triggerCooldownMillis) return false
            if (lastAcceptedTriggerAtMillis.compareAndSet(last, nowMillis)) return true
        }
    }

    /**
     * Returns only recipients in target domain when domain is specified.
     *
     * @param recipients full recipient list
     * @param targetDomain null for all recipients, otherwise only matching domain
     * @return effective recipients to process
     */
    private fun recipientsToProcess(recipients: List<String>, targetDomain: String?): List<String> {
        if (targetDomain == null) return recipients.toList()
        return recipients.filter { recipient ->
            val recipientDomain = recipient.substringAfterLast('@', "")
            normalizeDomain(recipientDomain) == targetDomain
        }
    }
}
