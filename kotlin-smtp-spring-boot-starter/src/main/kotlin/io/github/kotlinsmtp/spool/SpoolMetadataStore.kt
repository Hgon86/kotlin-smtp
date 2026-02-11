package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import io.github.oshai.kotlinlogging.KotlinLogging
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
 * 스풀 메타데이터 저장소 추상화입니다.
 */
interface SpoolMetadataStore {
    /**
     * 스풀 저장소가 사용할 디렉터리를 준비합니다.
     */
    fun initializeDirectory()

    /**
     * 현재 대기 중인 스풀 메시지 수를 계산합니다.
     *
     * @return 대기 메시지 수
     */
    fun scanPendingMessageCount(): Long

    /**
     * 스풀 메시지 원문 파일 목록을 조회합니다.
     *
     * @return 메시지 파일 경로 목록
     */
    fun listMessages(): List<Path>

    /**
     * 신규 스풀 메시지를 생성합니다.
     *
     * @param rawMessagePath 원본 RFC822 파일 경로
     * @param sender envelope sender
     * @param recipients 수신자 목록
     * @param messageId 메시지 식별자
     * @param authenticated 인증 여부
     * @param peerAddress 클라이언트 주소
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
        peerAddress: String? = null,
        dsnRet: String? = null,
        dsnEnvid: String? = null,
        rcptDsn: Map<String, RcptDsn> = emptyMap(),
    ): SpoolMetadata

    /**
     * 메타데이터를 저장합니다.
     *
     * @param meta 저장 대상 메타데이터
     */
    fun writeMeta(meta: SpoolMetadata)

    /**
     * 메타데이터를 읽습니다.
     *
     * @param rawPath 스풀 메시지 원문 경로
     * @return 파싱된 메타데이터, 없거나 파싱 실패 시 null
     */
    fun readMeta(rawPath: Path): SpoolMetadata?

    /**
     * 스풀 메시지 원문과 메타 파일을 함께 제거합니다.
     *
     * @param rawPath 삭제할 원문 파일 경로
     */
    fun removeMessage(rawPath: Path)

    /**
     * 배달 처리를 위한 원문 접근 경로를 준비합니다.
     *
     * 파일 기반 구현은 입력 경로를 그대로 반환하고,
     * 비파일 기반 구현은 임시 파일을 생성해 반환할 수 있습니다.
     *
     * @param rawPath 스풀 메시지 식별 경로
     * @return 배달에 사용할 원문 파일 경로
     */
    fun prepareRawMessageForDelivery(rawPath: Path): Path = rawPath

    /**
     * 배달 처리를 위해 준비한 원문 접근 경로를 정리합니다.
     *
     * @param preparedPath `prepareRawMessageForDelivery`로 획득한 경로
     */
    fun cleanupPreparedRawMessage(preparedPath: Path) = Unit
}

/**
 * 파일 기반 스풀 메타데이터 저장소를 담당합니다.
 *
 * @property spoolDir 스풀 디렉터리
 */
class FileSpoolMetadataStore(
    private val spoolDir: Path,
) : SpoolMetadataStore {
    /**
     * 스풀 디렉터리를 준비합니다.
     */
    override fun initializeDirectory() {
        Files.createDirectories(spoolDir)
    }

    /**
     * 현재 대기 중인 스풀 메시지 수를 계산합니다.
     *
     * @return `*.eml` 파일 개수
     */
    override fun scanPendingMessageCount(): Long = runCatching {
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
    override fun listMessages(): List<Path> = spoolDir.listDirectoryEntries("*.eml")

    /**
     * 신규 스풀 메시지를 생성합니다.
     *
     * @param rawMessagePath 원본 RFC822 파일 경로
     * @param sender envelope sender
     * @param recipients 수신자 목록
     * @param messageId 메시지 식별자
     * @param authenticated 인증 여부
     * @param peerAddress 클라이언트 주소
     * @param dsnRet DSN RET 옵션
     * @param dsnEnvid DSN ENVID 옵션
     * @param rcptDsn 수신자별 DSN 옵션
     * @return 생성된 스풀 메타데이터
     */
    override fun createMessage(
        rawMessagePath: Path,
        sender: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
        peerAddress: String?,
        dsnRet: String?,
        dsnEnvid: String?,
        rcptDsn: Map<String, RcptDsn>,
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
            peerAddress = peerAddress,
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
    override fun writeMeta(meta: SpoolMetadata) {
        val metaPath = metaPath(meta.rawPath)
        metaPath.writeText(SpoolMetadataJsonCodec.toJson(meta))
    }

    /**
     * 메타데이터를 읽습니다.
     *
     * @param rawPath 스풀 메시지 원문 경로
     * @return 파싱된 메타데이터, 없거나 파싱 실패 시 null
     */
    override fun readMeta(rawPath: Path): SpoolMetadata? = runCatching {
        val metaPath = metaPath(rawPath)
        if (metaPath.notExists()) return null
        SpoolMetadataJsonCodec.fromJson(rawPath, metaPath.readText())
    }.getOrElse { e ->
        metadataLog.warn(e) { "Failed to parse spool metadata: ${metaPath(rawPath)}" }
        null
    }

    /**
     * 스풀 메시지 원문과 메타 파일을 함께 제거합니다.
     *
     * @param rawPath 삭제할 원문 파일 경로
     */
    override fun removeMessage(rawPath: Path) {
        runCatching { Files.deleteIfExists(rawPath) }
        runCatching { Files.deleteIfExists(metaPath(rawPath)) }
    }

    private fun metaPath(rawPath: Path): Path =
        rawPath.resolveSibling(rawPath.fileName.toString().replace(".eml", ".json"))
}
