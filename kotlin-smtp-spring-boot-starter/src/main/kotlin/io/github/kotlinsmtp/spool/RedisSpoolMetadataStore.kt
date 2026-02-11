package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID

private val redisMetadataLog = KotlinLogging.logger {}

/**
 * Redis 기반 스풀 메타데이터 저장소입니다.
 *
 * 원문/큐/메타 상태를 Redis에 저장합니다.
 * 배달 시점에만 임시 파일로 원문을 물리화하여 하위 API(Path 기반)와 연결합니다.
 *
 * @property spoolDir 스풀 원문 디렉터리
 * @property redisTemplate Redis 문자열 템플릿
 * @property keyPrefix Redis 키 접두사
 * @property maxRawBytes Redis에 허용할 원문 최대 바이트
 */
internal class RedisSpoolMetadataStore(
    private val spoolDir: Path,
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
    private val maxRawBytes: Long,
) : SpoolMetadataStore {
    /**
     * 스풀 저장소가 사용할 디렉터리를 준비합니다.
     */
    override fun initializeDirectory() {
        Files.createDirectories(spoolDir)
    }

    /**
     * 현재 대기 중인 스풀 메시지 수를 계산합니다.
     *
     * @return 대기 메시지 수
     */
    override fun scanPendingMessageCount(): Long = runCatching {
        redisTemplate.opsForZSet().size(queueKey()) ?: 0L
    }.getOrElse { e ->
        redisMetadataLog.warn(e) { "Failed to scan pending spool messages from Redis" }
        0L
    }

    /**
     * 스풀 메시지 원문 파일 목록을 조회합니다.
     *
     * @return 메시지 파일 경로 목록
     */
    override fun listMessages(): List<Path> = runCatching {
        val members = redisTemplate.opsForZSet().range(queueKey(), 0, -1) ?: emptySet()
        members.map { Path.of(it) }
    }.getOrElse { e ->
        redisMetadataLog.warn(e) { "Failed to list spool messages from Redis" }
        emptyList()
    }

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
        val target = spoolDir.resolve("redis_msg_${Instant.now().toEpochMilli()}_${id}.eml")
        val rawSize = Files.size(rawMessagePath)
        require(rawSize in 1..maxRawBytes) {
            "Redis spool raw message size exceeds limit: size=$rawSize maxRawBytes=$maxRawBytes"
        }

        val rawBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(rawMessagePath))
        val metadata = SpoolMetadata(
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
        )

        val json = SpoolMetadataJsonCodec.toJson(metadata)
        executeAtomically { ops ->
            ops.opsForValue().set(rawKey(target), rawBase64)
            ops.opsForValue().set(metaKey(target), json)
            ops.opsForZSet().add(queueKey(), target.toString(), metadata.nextAttemptAt.toEpochMilli().toDouble())
        }
        return metadata
    }

    /**
     * 메타데이터를 저장합니다.
     *
     * @param meta 저장 대상 메타데이터
     */
    override fun writeMeta(meta: SpoolMetadata) {
        executeAtomically { ops ->
            ops.opsForValue().set(metaKey(meta.rawPath), SpoolMetadataJsonCodec.toJson(meta))
            ops.opsForZSet().add(queueKey(), meta.rawPath.toString(), meta.nextAttemptAt.toEpochMilli().toDouble())
        }
    }

    /**
     * 메타데이터를 읽습니다.
     *
     * @param rawPath 스풀 메시지 원문 경로
     * @return 파싱된 메타데이터, 없거나 파싱 실패 시 null
     */
    override fun readMeta(rawPath: Path): SpoolMetadata? = runCatching {
        val jsonRaw = redisTemplate.opsForValue().get(metaKey(rawPath)) ?: return null
        SpoolMetadataJsonCodec.fromJson(rawPath, jsonRaw)
    }.getOrElse { e ->
        redisMetadataLog.warn(e) { "Failed to parse spool metadata from Redis: ${rawPath}" }
        null
    }

    /**
     * 스풀 메시지 원문과 메타 파일을 함께 제거합니다.
     *
     * @param rawPath 삭제할 원문 파일 경로
     */
    override fun removeMessage(rawPath: Path) {
        runCatching {
            executeAtomically { ops ->
                ops.delete(metaKey(rawPath))
                ops.delete(rawKey(rawPath))
                ops.opsForZSet().remove(queueKey(), rawPath.toString())
            }
        }.onFailure { e ->
            redisMetadataLog.warn(e) { "Failed to remove spool message from Redis: $rawPath" }
        }
    }

    /**
     * Redis 원문을 임시 파일로 물리화해 배달 경로를 준비합니다.
     *
     * @param rawPath 스풀 메시지 식별 경로
     * @return 배달용 임시 원문 파일 경로
     */
    override fun prepareRawMessageForDelivery(rawPath: Path): Path {
        val encoded = redisTemplate.opsForValue().get(rawKey(rawPath))
            ?: throw SpoolCorruptedMessageException("Redis spool raw message not found: $rawPath")

        val tempDir = spoolDir.resolve(".redis-delivery")
        Files.createDirectories(tempDir)
        val tempFile = tempDir.resolve("delivery_${Instant.now().toEpochMilli()}_${UUID.randomUUID().toString().take(8)}.eml")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { e -> throw SpoolCorruptedMessageException("Invalid Redis spool raw payload: $rawPath", e) }
        Files.write(tempFile, bytes)
        return tempFile
    }

    /**
     * 배달용 임시 원문 파일을 정리합니다.
     *
     * @param preparedPath 삭제할 임시 원문 파일 경로
     */
    override fun cleanupPreparedRawMessage(preparedPath: Path) {
        runCatching { Files.deleteIfExists(preparedPath) }
    }

    private fun queueKey(): String = "$keyPrefix:queue"

    private fun metaKey(rawPath: Path): String = "$keyPrefix:meta:${rawPathToken(rawPath)}"

    private fun rawKey(rawPath: Path): String = "$keyPrefix:raw:${rawPathToken(rawPath)}"

    private fun rawPathToken(rawPath: Path): String =
        RedisSpoolKeyCodec.pathToken(rawPath)

    /**
     * Redis 키 갱신을 트랜잭션으로 실행합니다.
     *
     * @param mutator 트랜잭션 내 키 조작
     */
    private fun executeAtomically(mutator: (RedisOperations<String, String>) -> Unit) {
        redisTemplate.execute(object : SessionCallback<List<Any?>> {
            override fun <K : Any?, V : Any?> execute(operations: RedisOperations<K, V>): List<Any?>? {
                @Suppress("UNCHECKED_CAST")
                val stringOps = operations as RedisOperations<String, String>
                stringOps.multi()
                mutator(stringOps)
                return stringOps.exec()
            }
        })
    }
}
