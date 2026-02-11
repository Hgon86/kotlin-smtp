package io.github.kotlinsmtp.spool

import jakarta.annotation.PreDestroy
import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.metrics.SpoolMetrics
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.server.SpoolTriggerResult
import io.github.kotlinsmtp.server.SmtpDomainSpooler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.net.IDN
import kotlin.math.min
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/**
 * 단일 메시지 배달 시도 결과입니다.
 *
 * @property delivered 성공적으로 배달된 수신자 목록
 * @property transientFailures 일시적 실패한 수신자별 사유
 * @property permanentFailures 영구적 실패한 수신자별 사유
 */
private data class DeliveryAttemptResult(
    val delivered: List<String>,
    val transientFailures: Map<String, String>,
    val permanentFailures: Map<String, String>,
)

/**
 * 스풀러로 메일 전달과 재시도를 관리합니다.
 *
 * 저장소 구현체에 따라 파일 또는 Redis에 메시지를 저장하며,
 * 백그라운드 워커가 재시도 간격을 두고 반복 전달을 시도합니다.
 *
 * @param spoolDir 스풀 디렉터리 경로
 * @param maxRetries 최대 재시도 횟수
 * @param retryDelaySeconds 초기 재시도 지연 시간(초)
 * @param dispatcher 코루틴 디스패처
 * @param deliveryService 메일 전달 서비스
 * @param dsnSenderProvider DSN 발송기 제공자
 * @param spoolMetrics 스풀 메트릭 수집기
 * @param metadataStore 스풀 메타데이터 저장소 구현체
 * @param lockManager 스풀 락 관리자 구현체
 */
class MailSpooler(
    private val spoolDir: Path,
    private val maxRetries: Int = 5,
    private val retryDelaySeconds: Long = 60,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deliveryService: MailDeliveryService,
    private val dsnSenderProvider: () -> DsnSender?,
    private val spoolMetrics: SpoolMetrics = SpoolMetrics.NOOP,
    injectedMetadataStore: SpoolMetadataStore? = null,
    injectedLockManager: SpoolLockManager? = null,
) : SmtpDomainSpooler {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
    private var worker: Job? = null
    private val staleLockThreshold = Duration.ofMinutes(15)
    private val runMutex = Mutex() // triggerOnce()와 백그라운드 워커의 동시 실행 방지
    private val lockManager = injectedLockManager ?: FileSpoolLockManager(spoolDir, staleLockThreshold)
    private val failurePolicy = SpoolFailurePolicy()
    private val metadataStore = injectedMetadataStore ?: FileSpoolMetadataStore(spoolDir)
    private val triggerDispatcher = SpoolTriggerDispatcher(scope) { domain -> runOnce(domain) }
    private val lockRefreshIntervalMillis = 30_000L

    init {
        metadataStore.initializeDirectory()
        spoolMetrics.initializePending(metadataStore.scanPendingMessageCount())
        start()
    }

    /**
     * 외부 트리거로 "즉시 한 번" 스풀 처리를 수행합니다.
     *
     * - 기능 우선: 운영/관리 시나리오에서 유용(예: ETRN)
     * - 동시 실행 제어: 프로세스 내에서는 뮤텍스로 1회 실행을 직렬화합니다.
     * - TODO: rate-limit(관리 기능 남용 방지)
     *
     * @return 트리거 요청 접수 결과
     */
    override fun tryTriggerOnce(): SpoolTriggerResult {
        triggerDispatcher.submit(targetDomain = null)
        return SpoolTriggerResult.ACCEPTED
    }

    override fun triggerOnce() {
        tryTriggerOnce()
    }

    /**
     * 지정 도메인에 대해서만 "즉시 한 번" 스풀 처리를 수행합니다.
     *
     * @param domain ETRN 인자로 전달된 도메인
     * @return 트리거 요청 접수 결과
     */
    override fun tryTriggerOnce(domain: String): SpoolTriggerResult {
        val normalized = normalizeDomain(domain)
        if (normalized == null) {
            log.warn { "Spooler triggerOnce(domain) skipped: invalid domain=$domain" }
            return SpoolTriggerResult.INVALID_ARGUMENT
        }

        triggerDispatcher.submit(targetDomain = normalized)
        return SpoolTriggerResult.ACCEPTED
    }

    override fun triggerOnce(domain: String) {
        tryTriggerOnce(domain)
    }

    /**
     * 메시지를 스풀에 등록합니다.
     *
     * @param rawMessagePath 원본 메시지 파일 경로
     * @param sender envelope 발신자
     * @param recipients 수신자 목록
     * @param messageId 메시지 식별자
     * @param authenticated 인증된 메시지 여부
     * @param peerAddress 클라이언트 주소
     * @param dsnRet DSN RET 옵션
     * @param dsnEnvid DSN ENVID 옵션
     * @param rcptDsn 수신자별 DSN 옵션
     * @return 생성된 스풀 메타데이터
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
     * 백그라운드 워커를 시작합니다.
     *
     * 재시도 간격을 두고 스풀 큐를 반복 처리합니다.
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
     * 스풀러 워커를 종료하고 리소스를 정리합니다.
     */
    @PreDestroy
    fun shutdown() {
        worker?.cancel()
        worker = null
        scope.cancel("MailSpooler shutdown")
    }

    /**
     * 스풀 큐를 한 번 처리합니다.
     *
     * 프로세스 내에서 뮤텍스로 직렬화하여 중복 처리를 방지합니다.
     *
     * @param targetDomain 특정 도메인만 처리하려면 해당 도메인, null이면 전체 큐 처리
     */
    private suspend fun runOnce(targetDomain: String? = null) {
        // 기능 우선: 단일 노드 기준으로는 파일락(.lock)만으로도 중복 처리를 대부분 방지하지만,
        // triggerOnce()가 연속 호출되거나 워커 루프와 겹치면 불필요한 스캔/락 시도가 발생합니다.
        // 따라서 프로세스 내에서는 뮤텍스로 1회 실행을 직렬화합니다.
        runMutex.withLock {
            processQueueOnce(targetDomain)
            lockManager.purgeOrphanedLocks()
        }
    }

    /**
     * 재시도 지연 시간을 계산합니다.
     *
     * 지수 백오프에 지터를 적용하며, 최대 10분으로 제한합니다.
     *
     * @param attempt 현재 시도 횟수
     * @return 다음 재시도까지 대기할 시간(초)
     */
    private fun nextBackoffSeconds(attempt: Int): Long {
        val base = retryDelaySeconds.toDouble()
        val exp = base * (1 shl (attempt - 1))
        val bounded = min(600.0, exp)
        val jitterFactor = 0.8 + Random.nextDouble() * 0.4
        return (bounded * jitterFactor).toLong().coerceAtLeast(retryDelaySeconds)
    }

    /**
     * 스풀 디렉터리의 모든 메시지를 한 번씩 처리합니다.
     *
     * @param targetDomain 특정 도메인만 처리하려면 해당 도메인, null이면 전체 큐 처리
     */
    private suspend fun processQueueOnce(targetDomain: String? = null) {
        val files = metadataStore.listMessages()
        for (file in files) {
            if (!lockManager.tryLock(file)) continue
            try {
                processSingleMessage(file, targetDomain)
            } finally {
                lockManager.unlock(file)
            }
        }
    }

    /**
     * 단일 스풀 메시지를 처리합니다.
     *
     * 메타데이터 로드 → 수신자 전달 → 결과 분류 → DSN/재시도 처리를 수행합니다.
     *
     * @param file 스풀 메시지 파일 경로
     * @param targetDomain 특정 도메인만 처리하려면 해당 도메인, null이면 전체 수신자 처리
     */
    private suspend fun processSingleMessage(file: Path, targetDomain: String?) {
        val meta = metadataStore.readMeta(file) ?: return
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
     * 락을 유지하면서 메시지 처리 블록을 수행합니다.
     *
     * @param spoolReferencePath 스풀 메시지 식별 경로
     * @param block 실제 처리 블록
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
     * 배달 준비 단계 실패를 분류해 후속 처리를 수행합니다.
     *
     * @param spoolReferencePath 스풀 메시지 식별 경로
     * @param meta 스풀 메타데이터
     * @param throwable 발생 예외
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
     * 전달이 끝난 메시지를 스풀에서 제거합니다.
     *
     * @param spoolReferencePath 스풀 메시지 식별 경로
     * @param meta 스풀 메타데이터
     */
    private fun completeMessage(spoolReferencePath: Path, meta: SpoolMetadata) {
        metadataStore.removeMessage(spoolReferencePath)
        spoolMetrics.onCompleted()
        log.info { "Spool completed and removed: $spoolReferencePath (id=${meta.id})" }
    }

    /**
     * 수신자 목록에 대해 전달을 시도합니다.
     *
     * @param meta 스풀 메타데이터
     * @param recipientsToProcess 처리할 수신자 목록
     * @param file 메시지 파일 경로
     * @return 배달 시도 결과
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
                    // 스풀러가 재시도/최종 DSN을 책임지므로 여기서는 DSN을 생성하지 않습니다.
                    generateDsnOnFailure = false,
                )
            }.onSuccess {
                delivered.add(rcpt)
            }.onFailure { t ->
                if (t is CancellationException) throw t
                val reason = t.message ?: t::class.simpleName.orEmpty()
                val permanent = failurePolicy.isPermanentFailure(t)
                if (permanent) permanentFailures[rcpt] = reason else transientFailures[rcpt] = reason
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
     * 배달 결과에 따라 수신자 목록을 업데이트합니다.
     *
     * 성공/영구 실패한 수신자를 제거하여 중복 전달을 방지합니다.
     *
     * @param meta 스풀 메타데이터
     * @param delivered 성공한 수신자 목록
     * @param permanentFailureRecipients 영구 실패한 수신자 집합
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
     * 영구 실패 수신자에 대한 DSN을 발송합니다.
     *
     * @param meta 스풀 메타데이터
     * @param file 메시지 파일 경로
     * @param dsnTargets DSN 발송 대상 수신자와 사유
     * @param rcptDsnSnapshot DSN 옵션 스냅샷
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
     * 일시적 실패한 수신자에 대한 후속 처리를 수행합니다.
     *
     * 재시도 스케줄링 또는 최대 재시도 초과 시 폐기를 결정합니다.
     *
     * @param meta 스풀 메타데이터
     * @param spoolReferencePath 스풀 메시지 식별 경로
     * @param deliveryRawPath 배달에 사용한 원문 파일 경로
     * @param transientFailures 일시 실패한 수신자와 사유
     * @param attemptedAllRecipients 전체 수신자 처리 여부
     * @param targetDomain 처리 대상 도메인(ETRN용)
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
            // 방어: 영구 실패 처리 후에도 남은 수신자가 있다면 메타를 저장합니다.
            metadataStore.writeMeta(meta)
            log.info { "Spool state saved without retry: id=${meta.id} remainingRcpt=${meta.recipients.size}" }
            return
        }

        if (!attemptedAllRecipients) {
            // ETRN 도메인 트리거처럼 일부 수신자만 처리한 경우,
            // 메시지 전역 attempt/backoff를 올리면 미처리 수신자까지 페널티를 받습니다.
            // 부분 처리에서는 상태만 저장하고, 전역 재시도 카운터는 유지합니다.
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
        spoolMetrics.onRetryScheduled()
        log.info {
            "Spool retry scheduled: id=${meta.id} attempt=${meta.attempt} remainingRcpt=${meta.recipients.size} next=${meta.nextAttemptAt} (backoff=${backoff}s)"
        }
    }

    /**
     * 도메인을 IDNA ASCII 소문자로 정규화합니다.
     *
     * @param domain 비교 대상 도메인
     * @return 정규화된 도메인 또는 유효하지 않으면 null
     */
    private fun normalizeDomain(domain: String): String? = runCatching {
        val trimmed = domain.trim().trimEnd('.')
        if (trimmed.isEmpty()) return null
        IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).lowercase()
    }.getOrNull()

    /**
     * 대상 도메인이 지정되면 해당 도메인 수신자만 반환합니다.
     *
     * @param recipients 전체 수신자 목록
     * @param targetDomain null이면 전체, 아니면 지정 도메인만 처리
     * @return 실제 처리 대상 수신자 목록
     */
    private fun recipientsToProcess(recipients: List<String>, targetDomain: String?): List<String> {
        if (targetDomain == null) return recipients.toList()
        return recipients.filter { recipient ->
            val recipientDomain = recipient.substringAfterLast('@', "")
            normalizeDomain(recipientDomain) == targetDomain
        }
    }
}
