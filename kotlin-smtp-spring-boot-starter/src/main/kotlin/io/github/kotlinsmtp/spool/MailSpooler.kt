package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.RelayException
import io.github.kotlinsmtp.server.SmtpDomainSpooler
import io.github.kotlinsmtp.server.SmtpSpooler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.net.UnknownHostException
import java.net.IDN
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.min
import kotlin.random.Random

private val log = KotlinLogging.logger {}

class MailSpooler(
    private val spoolDir: Path,
    private val maxRetries: Int = 5,
    private val retryDelaySeconds: Long = 60,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deliveryService: MailDeliveryService,
    private val dsnSenderProvider: () -> DsnSender?,
) : SmtpDomainSpooler {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
    private var worker: Job? = null
    private val staleLockThreshold = Duration.ofMinutes(15)
    private val runMutex = Mutex() // triggerOnce()와 백그라운드 워커의 동시 실행 방지
    private val triggerStateLock = Any()
    private var triggerDrainerRunning = false
    private val triggerCoalescer = TriggerCoalescer()

    init {
        Files.createDirectories(spoolDir)
        start()
    }

    /**
     * 외부 트리거로 "즉시 한 번" 스풀 처리를 수행합니다.
     *
     * - 기능 우선: 운영/관리 시나리오에서 유용(예: ETRN)
     * - 동시 실행 제어: 프로세스 내에서는 뮤텍스로 1회 실행을 직렬화합니다.
     * - TODO: rate-limit(관리 기능 남용 방지)
     */
    override fun triggerOnce() {
        submitTrigger(targetDomain = null)
    }

    /**
     * 지정 도메인에 대해서만 "즉시 한 번" 스풀 처리를 수행합니다.
     *
     * @param domain ETRN 인자로 전달된 도메인
     */
    override fun triggerOnce(domain: String) {
        val normalized = normalizeDomain(domain)
        if (normalized == null) {
            log.warn { "Spooler triggerOnce(domain) skipped: invalid domain=$domain" }
            return
        }

        submitTrigger(targetDomain = normalized)
    }

    /**
     * 외부 트리거 요청을 coalescing 큐에 적재하고 드레이너를 시작합니다.
     *
     * @param targetDomain null이면 전체 큐 트리거, 값이 있으면 해당 도메인만 트리거
     */
    private fun submitTrigger(targetDomain: String?) {
        val shouldStartDrainer = synchronized(triggerStateLock) {
                triggerCoalescer.submit(targetDomain)
                if (triggerDrainerRunning) {
                    false
                } else {
                    triggerDrainerRunning = true
                    true
                }
        }

        if (shouldStartDrainer) {
            scope.launch { drainPendingTriggers() }
        }
    }

    /**
     * 적재된 트리거를 순차 실행합니다.
     */
    private suspend fun drainPendingTriggers() {
        while (true) {
            val nextTarget = synchronized(triggerStateLock) {
                when (val next = triggerCoalescer.poll()) {
                    is SpoolTrigger.Full -> null
                    is SpoolTrigger.Domain -> next.domain
                    null -> {
                        triggerDrainerRunning = false
                        return
                    }
                }
            }

            runCatching {
                runOnce(nextTarget)
            }.onFailure { e ->
                if (nextTarget == null) {
                    log.warn(e) { "Spooler triggerOnce failed" }
                } else {
                    log.warn(e) { "Spooler triggerOnce(domain) failed domain=$nextTarget" }
                }
            }
        }
    }

    fun enqueue(
        rawMessagePath: Path,
        sender: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
        dsnRet: String? = null,
        dsnEnvid: String? = null,
        rcptDsn: Map<String, RcptDsn> = emptyMap(),
    ): SpoolMetadata {
        // TODO(storage): 현재는 로컬 파일 스풀(.eml/.json)이지만,
        //               - DB(메타+본문), S3(본문) + DB(메타) 등으로 쉽게 전환할 수 있도록 MessageStore 추상화 고려
        //               - 스풀 메타에 storageUri(s3://bucket/key 등), traceId 등을 추가하는 방향이 유리
        val id = UUID.randomUUID().toString().take(8)
        val target = spoolDir.resolve("msg_${Instant.now().toEpochMilli()}_${id}.eml")
        Files.copy(rawMessagePath, target, StandardCopyOption.REPLACE_EXISTING)
        val meta = SpoolMetadata(
            id = id,
            rawPath = target,
            sender = sender,
            recipients = recipients.toMutableList(),
            messageId = messageId,
            authenticated = authenticated,
            dsnRet = dsnRet,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn.toMutableMap(),
        )
        writeMeta(meta)
        log.info { "Spool queued: $target (recipients=${recipients.size})" }
        return meta
    }

    private fun writeMeta(meta: SpoolMetadata) {
        val metaPath = metaPath(meta.rawPath)
        val json = JSONObject()
            .put("id", meta.id)
            .put("attempt", meta.attempt)
            .put("next", meta.nextAttemptAt.toEpochMilli())
            .put("sender", meta.sender ?: "")
            .put("recipients", meta.recipients)
            .put("messageId", meta.messageId)
            .put("authenticated", meta.authenticated)
            .put("dsnRet", meta.dsnRet ?: "")
            .put("dsnEnvid", meta.dsnEnvid ?: "")

        // 수신자별 DSN 옵션 (NOTIFY/ORCPT)
        val rcptDsnJson = JSONObject()
        for ((rcpt, dsn) in meta.rcptDsn) {
            rcptDsnJson.put(
                rcpt,
                JSONObject()
                    .put("notify", dsn.notify ?: "")
                    .put("orcpt", dsn.orcpt ?: "")
            )
        }
        json.put("rcptDsn", rcptDsnJson)

        metaPath.writeText(json.toString())
    }

    private fun readMeta(path: Path): SpoolMetadata? = runCatching {
        val metaPath = metaPath(path)
        if (metaPath.notExists()) return null
        val json = JSONObject(metaPath.readText())
        val id = json.getString("id")
        val attempt = json.optInt("attempt", 0)
        val next = json.optLong("next", Instant.now().toEpochMilli())
        val sender = json.optString("sender").ifBlank { null }
        val recipientsJson = json.optJSONArray("recipients")
        val recipients = buildList {
            if (recipientsJson != null) {
                for (i in 0 until recipientsJson.length()) add(recipientsJson.getString(i))
            }
        }.toMutableList()
        val messageId = json.optString("messageId", "?")
        val authenticated = json.optBoolean("authenticated", false)
        val dsnRet = json.optString("dsnRet").ifBlank { null }
        val dsnEnvid = json.optString("dsnEnvid").ifBlank { null }

        val rcptDsnObj = json.optJSONObject("rcptDsn")
        val rcptDsn = linkedMapOf<String, RcptDsn>()
        if (rcptDsnObj != null) {
            for (key in rcptDsnObj.keySet()) {
                val obj = rcptDsnObj.optJSONObject(key) ?: continue
                val notify = obj.optString("notify").ifBlank { null }
                val orcpt = obj.optString("orcpt").ifBlank { null }
                rcptDsn[key] = RcptDsn(notify = notify, orcpt = orcpt)
            }
        }

        SpoolMetadata(
            id = id,
            rawPath = path,
            sender = sender,
            recipients = recipients,
            messageId = messageId,
            authenticated = authenticated,
            dsnRet = dsnRet,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn.toMutableMap(),
            attempt = attempt,
            nextAttemptAt = Instant.ofEpochMilli(next)
        )
    }.getOrNull()

    private fun metaPath(rawPath: Path): Path = rawPath.resolveSibling(rawPath.fileName.toString().replace(".eml", ".json"))
    private fun lockPath(rawPath: Path): Path = rawPath.resolveSibling(rawPath.fileName.toString().replace(".eml", ".lock"))

    private fun tryLock(rawPath: Path): Boolean {
        val lock = lockPath(rawPath)
        return try {
            // 원자적으로 락 파일을 생성(CREATE_NEW)해 경합 시 중복 처리/레이스 컨디션을 방지합니다.
            Files.writeString(
                lock,
                Instant.now().toEpochMilli().toString(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            true
        } catch (_: FileAlreadyExistsException) {
            false
        } catch (e: Exception) {
            log.warn(e) { "Failed to create spool lock: $lock" }
            false
        }
    }

    private fun unlock(rawPath: Path) {
        runCatching { Files.deleteIfExists(lockPath(rawPath)) }
    }

    private fun start() {
        if (worker != null) return
        worker = scope.launch {
            while (true) {
                try {
                    runOnce()
                } catch (e: Exception) {
                    log.warn(e) { "Spooler worker loop error" }
                } finally {
                    delay(retryDelaySeconds * 1000)
                }
            }
        }
    }

    private suspend fun runOnce(targetDomain: String? = null) {
        // 기능 우선: 단일 노드 기준으로는 파일락(.lock)만으로도 중복 처리를 대부분 방지하지만,
        // triggerOnce()가 연속 호출되거나 워커 루프와 겹치면 불필요한 스캔/락 시도가 발생합니다.
        // 따라서 프로세스 내에서는 뮤텍스로 1회 실행을 직렬화합니다.
        runMutex.withLock {
            processQueueOnce(targetDomain)
            purgeOrphanedLocks()
        }
    }

    private fun nextBackoffSeconds(attempt: Int): Long {
        val base = retryDelaySeconds.toDouble()
        val exp = base * (1 shl (attempt - 1))
        val bounded = min(600.0, exp)
        val jitterFactor = 0.8 + Random.nextDouble() * 0.4
        return (bounded * jitterFactor).toLong().coerceAtLeast(retryDelaySeconds)
    }

    private suspend fun processQueueOnce(targetDomain: String? = null) {
        val files = spoolDir.listDirectoryEntries("*.eml")
        for (file in files) {
            if (!tryLock(file)) continue
            try {
                val meta = readMeta(file) ?: continue
                if (meta.nextAttemptAt.isAfter(Instant.now())) continue

                val recipientsToProcess = recipientsToProcess(meta.recipients, targetDomain)
                if (recipientsToProcess.isEmpty()) continue
                val attemptedAllRecipients = recipientsToProcess.size == meta.recipients.size

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
                            // 스풀러가 재시도/최종 DSN을 책임지므로 여기서는 DSN을 생성하지 않습니다.
                            generateDsnOnFailure = false,
                        )
                    }.onSuccess {
                        delivered.add(rcpt)
                    }.onFailure { t ->
                        val reason = t.message ?: t::class.simpleName.orEmpty()
                        val permanent = isPermanentFailure(t)
                        if (permanent) permanentFailures[rcpt] = reason else transientFailures[rcpt] = reason
                        log.warn(t) { "Spool delivery failed for rcpt=$rcpt (id=${meta.id}) permanent=$permanent" }
                    }
                }

                // 성공/영구실패 수신자는 제거하여 중복 전달을 방지합니다.
                if (delivered.isNotEmpty() || permanentFailures.isNotEmpty()) {
                    meta.recipients.removeAll(delivered.toSet())
                    meta.recipients.removeAll(permanentFailures.keys)
                    meta.rcptDsn.keys.removeAll(delivered.toSet())
                    meta.rcptDsn.keys.removeAll(permanentFailures.keys)
                    log.info { "Spool recipient update: id=${meta.id} delivered=${delivered.size} permanentFail=${permanentFailures.size} remaining=${meta.recipients.size}" }
                }

                // 영구 실패는 즉시 DSN을 발송합니다(지연 없이).
                if (permanentFailures.isNotEmpty()) {
                    val dsnTargets = permanentFailures
                        .filterKeys { shouldSendFailureDsn(meta.rcptDsn[it]?.notify) }
                    if (dsnTargets.isNotEmpty()) {
                        dsnSenderProvider()?.sendPermanentFailure(
                            sender = meta.sender,
                            failedRecipients = dsnTargets.entries.map { it.key to it.value },
                            originalMessageId = meta.messageId,
                            originalMessagePath = file,
                            dsnEnvid = meta.dsnEnvid,
                            dsnRet = meta.dsnRet,
                            rcptDsn = meta.rcptDsn.filterKeys { it in dsnTargets.keys },
                        )
                    }
                }

                // 남은 수신자가 없으면 메시지를 제거하고 종료합니다.
                if (meta.recipients.isEmpty()) {
                    runCatching { Files.deleteIfExists(file) }
                    runCatching { Files.deleteIfExists(metaPath(file)) }
                    log.info { "Spool completed and removed: $file (id=${meta.id})" }
                    continue
                }

                // 일시 실패가 남아 있으면 재시도 스케줄링
                if (transientFailures.isNotEmpty()) {
                    if (!attemptedAllRecipients) {
                        // ETRN 도메인 트리거처럼 일부 수신자만 처리한 경우,
                        // 메시지 전역 attempt/backoff를 올리면 미처리 수신자까지 페널티를 받습니다.
                        // 부분 처리에서는 상태만 저장하고, 전역 재시도 카운터는 유지합니다.
                        writeMeta(meta)
                        log.info {
                            "Spool partial run saved without retry increment: id=${meta.id} targetDomain=$targetDomain transientFail=${transientFailures.size} remainingRcpt=${meta.recipients.size}"
                        }
                        continue
                    }

                    meta.attempt += 1
                    if (meta.attempt >= maxRetries) {
                        log.warn { "Spool drop after max retries: $file (id=${meta.id})" }
                        runCatching { Files.deleteIfExists(file) }
                        runCatching { Files.deleteIfExists(metaPath(file)) }
                        val dsnTargets = transientFailures
                            .filterKeys { shouldSendFailureDsn(meta.rcptDsn[it]?.notify) }
                        val details = dsnTargets.entries.map { it.key to it.value }
                        if (details.isNotEmpty()) {
                            dsnSenderProvider()?.sendPermanentFailure(
                                sender = meta.sender,
                                failedRecipients = details,
                                originalMessageId = meta.messageId,
                                originalMessagePath = file,
                                dsnEnvid = meta.dsnEnvid,
                                dsnRet = meta.dsnRet,
                                rcptDsn = meta.rcptDsn.filterKeys { it in dsnTargets.keys },
                            )
                        }
                    } else {
                        val backoff = nextBackoffSeconds(meta.attempt)
                        meta.nextAttemptAt = Instant.now().plusSeconds(backoff)
                        writeMeta(meta)
                        log.info { "Spool retry scheduled: id=${meta.id} attempt=${meta.attempt} remainingRcpt=${meta.recipients.size} next=${meta.nextAttemptAt} (backoff=${backoff}s)" }
                    }
                } else {
                    // 방어: 영구 실패 처리 후에도 남은 수신자가 있다면 메타를 저장합니다.
                    writeMeta(meta)
                    log.info { "Spool state saved without retry: id=${meta.id} remainingRcpt=${meta.recipients.size}" }
                }
            } finally {
                unlock(file)
            }
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

    /**
     * NOTIFY 파라미터를 최소한으로 반영해 불필요한 DSN(특히 NOTIFY=NEVER)을 억제합니다.
     *
     * RFC 3461 기본값/세부 규칙은 구현 범위 밖이므로, 실사용에 안전한(보수적) 규칙으로 둡니다.
     *
     * @param notify RCPT 단위 NOTIFY 파라미터 원문
     * @return FAILURE DSN을 발송해야 하면 true
     */
    private fun shouldSendFailureDsn(notify: String?): Boolean {
        val tokens = parseNotifyTokens(notify) ?: return true
        if ("NEVER" in tokens) return false
        return "FAILURE" in tokens || tokens.isEmpty()
    }

    /**
     * NOTIFY 파라미터를 토큰 집합으로 파싱합니다.
     *
     * @param notify RCPT 단위 NOTIFY 파라미터 원문
     * @return 파싱 결과 토큰 집합, 입력이 비어 있으면 null
     */
    private fun parseNotifyTokens(notify: String?): Set<String>? {
        val raw = notify?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw
            .split(',')
            .asSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 재시도 여부(일시/영구 실패)를 보수적으로 분류합니다.
     * - 기본은 "일시 실패(재시도)"로 두고, 확실한 5xx/정책/구성 오류만 영구 실패로 판단합니다.
     *
     * @param t 전달 실패 원인 예외
     * @return 영구 실패로 분류되면 true
     */
    private fun isPermanentFailure(t: Throwable): Boolean {
        when (t) {
            is io.github.kotlinsmtp.exception.SmtpSendResponse -> return t.statusCode in 500..599
            is RelayException -> return !t.isTransient
            is IllegalStateException -> {
                val m = t.message.orEmpty()
                return m.contains("No MX records", ignoreCase = true) || m.contains("No valid MX", ignoreCase = true)
            }
            is UnknownHostException -> return false
        }

        val code = smtpReturnCodeOrNull(t)
        if (code != null) return code in 500..599

        val enhanced = enhancedCodeOrNull(t.message)
        if (enhanced != null) return enhanced.first() == '5'

        return false // 기본: 일시 실패(재시도)
    }

    private fun smtpReturnCodeOrNull(t: Throwable): Int? = runCatching {
        val m = t.javaClass.methods.firstOrNull { it.name == "getReturnCode" && it.parameterCount == 0 } ?: return null
        (m.invoke(t) as? Int)
    }.getOrNull()

    private fun enhancedCodeOrNull(message: String?): String? {
        if (message.isNullOrBlank()) return null
        val m = Regex("\\b(\\d\\.\\d\\.\\d)\\b").find(message) ?: return null
        return m.groupValues.getOrNull(1)
    }

    private fun purgeOrphanedLocks() {
        val now = Instant.now()
        spoolDir.listDirectoryEntries("*.lock").forEach { lock ->
            val emlPath = lock.resolveSibling(lock.fileName.toString().replace(".lock", ".eml"))
            if (emlPath.notExists()) {
                runCatching { Files.deleteIfExists(lock) }
                return@forEach
            }
            val timestampMs = runCatching { lock.readText().toLong() }.getOrDefault(0L)
            if (timestampMs > 0) {
                val age = Duration.ofMillis(now.toEpochMilli() - timestampMs)
                if (age > staleLockThreshold) {
                    log.warn { "Removing stale lock: $lock" }
                    runCatching { Files.deleteIfExists(lock) }
                }
            }
        }
    }
}
