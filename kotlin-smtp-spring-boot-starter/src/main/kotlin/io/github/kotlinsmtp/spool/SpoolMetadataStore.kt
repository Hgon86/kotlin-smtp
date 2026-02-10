package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import io.github.oshai.kotlinlogging.KotlinLogging
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val metadataLog = KotlinLogging.logger {}

/**
 * 파일 기반 스풀 메타데이터 저장소를 담당합니다.
 *
 * @property spoolDir 스풀 디렉터리
 */
internal class SpoolMetadataStore(
    private val spoolDir: Path,
) {
    /**
     * 스풀 디렉터리를 준비합니다.
     */
    fun initializeDirectory() {
        Files.createDirectories(spoolDir)
    }

    /**
     * 현재 대기 중인 스풀 메시지 수를 계산합니다.
     *
     * @return `*.eml` 파일 개수
     */
    fun scanPendingMessageCount(): Long = runCatching {
        Files.list(spoolDir).use { stream ->
            stream.filter { path -> path.fileName.toString().endsWith(".eml") }.count()
        }
    }.getOrElse { e ->
        metadataLog.warn(e) { "Failed to scan pending spool messages" }
        0L
    }

    /**
     * 스풀 메시지 파일 목록을 조회합니다.
     *
     * @return 스풀 메시지 경로 목록
     */
    fun listMessages(): List<Path> = spoolDir.listDirectoryEntries("*.eml")

    /**
     * 신규 스풀 메시지를 생성합니다.
     *
     * @param rawMessagePath 원본 RFC822 파일 경로
     * @param sender envelope sender
     * @param recipients 수신자 목록
     * @param messageId 메시지 식별자
     * @param authenticated 인증 여부
     * @param dsnRet DSN RET 옵션
     * @param dsnEnvid DSN ENVID 옵션
     * @param rcptDsn 수신자별 DSN 옵션
     * @return 생성된 스풀 메타데이터
     */
    fun createMessage(
        rawMessagePath: Path,
        sender: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
        dsnRet: String? = null,
        dsnEnvid: String? = null,
        rcptDsn: Map<String, RcptDsn> = emptyMap(),
    ): SpoolMetadata {
        val id = UUID.randomUUID().toString().take(8)
        val target = spoolDir.resolve("msg_${Instant.now().toEpochMilli()}_${id}.eml")
        Files.copy(rawMessagePath, target, StandardCopyOption.REPLACE_EXISTING)

        return SpoolMetadata(
            id = id,
            rawPath = target,
            sender = sender,
            recipients = recipients.toMutableList(),
            messageId = messageId,
            authenticated = authenticated,
            dsnRet = dsnRet,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn.toMutableMap(),
        ).also { writeMeta(it) }
    }

    /**
     * 메타데이터를 저장합니다.
     *
     * @param meta 저장 대상 메타데이터
     */
    fun writeMeta(meta: SpoolMetadata) {
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

        val rcptDsnJson = JSONObject()
        for ((rcpt, dsn) in meta.rcptDsn) {
            rcptDsnJson.put(
                rcpt,
                JSONObject()
                    .put("notify", dsn.notify ?: "")
                    .put("orcpt", dsn.orcpt ?: ""),
            )
        }
        json.put("rcptDsn", rcptDsnJson)

        metaPath.writeText(json.toString())
    }

    /**
     * 메타데이터를 읽습니다.
     *
     * @param rawPath 스풀 메시지 원문 경로
     * @return 파싱된 메타데이터, 없거나 파싱 실패 시 null
     */
    fun readMeta(rawPath: Path): SpoolMetadata? = runCatching {
        val metaPath = metaPath(rawPath)
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
            rawPath = rawPath,
            sender = sender,
            recipients = recipients,
            messageId = messageId,
            authenticated = authenticated,
            dsnRet = dsnRet,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn.toMutableMap(),
            attempt = attempt,
            nextAttemptAt = Instant.ofEpochMilli(next),
        )
    }.getOrElse { e ->
        metadataLog.warn(e) { "Failed to parse spool metadata: ${metaPath(rawPath)}" }
        null
    }

    /**
     * 스풀 메시지 원문과 메타 파일을 함께 제거합니다.
     *
     * @param rawPath 삭제할 원문 파일 경로
     */
    fun removeMessage(rawPath: Path) {
        runCatching { Files.deleteIfExists(rawPath) }
        runCatching { Files.deleteIfExists(metaPath(rawPath)) }
    }

    private fun metaPath(rawPath: Path): Path =
        rawPath.resolveSibling(rawPath.fileName.toString().replace(".eml", ".json"))
}
