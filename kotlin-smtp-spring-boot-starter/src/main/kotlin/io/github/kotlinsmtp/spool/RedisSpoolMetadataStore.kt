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
 * Redis-based spool metadata store.
 *
 * Stores raw/queue/metadata state in Redis.
 * Materializes raw payload to a temporary file only at delivery time for Path-based APIs.
 *
 * @property spoolDir spool raw directory
 * @property redisTemplate Redis string template
 * @property keyPrefix Redis key prefix
 * @property maxRawBytes max allowed raw bytes in Redis
 */
internal class RedisSpoolMetadataStore(
    private val spoolDir: Path,
    private val redisTemplate: StringRedisTemplate,
    private val keyPrefix: String,
    private val maxRawBytes: Long,
) : SpoolMetadataStore {
    /**
     * Prepares directory used by spool storage.
     */
    override fun initializeDirectory() {
        Files.createDirectories(spoolDir)
    }

    /**
     * Counts currently pending spool messages.
     *
     * @return pending message count
     */
    override fun scanPendingMessageCount(): Long = runCatching {
        redisTemplate.opsForZSet().size(queueKey()) ?: 0L
    }.getOrElse { e ->
        redisMetadataLog.warn(e) { "Failed to scan pending spool messages from Redis" }
        0L
    }

    /**
     * Lists raw file references of spool messages.
     *
     * @return message file path list
     */
    override fun listMessages(): List<Path> = runCatching {
        val members = redisTemplate.opsForZSet().range(queueKey(), 0, -1) ?: emptySet()
        members.map { Path.of(it) }
    }.getOrElse { e ->
        redisMetadataLog.warn(e) { "Failed to list spool messages from Redis" }
        emptyList()
    }

    /**
     * Creates a new spool message.
     *
     * @param rawMessagePath source RFC822 file path
     * @param sender envelope sender
     * @param recipients recipient list
     * @param messageId message identifier
     * @param authenticated authentication state
     * @param peerAddress client address
     * @param dsnRet DSN RET option
     * @param dsnEnvid DSN ENVID option
     * @param rcptDsn per-recipient DSN options
     * @return created spool metadata
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
     * Persists metadata.
     *
     * @param meta metadata to persist
     */
    override fun writeMeta(meta: SpoolMetadata) {
        executeAtomically { ops ->
            ops.opsForValue().set(metaKey(meta.rawPath), SpoolMetadataJsonCodec.toJson(meta))
            ops.opsForZSet().add(queueKey(), meta.rawPath.toString(), meta.nextAttemptAt.toEpochMilli().toDouble())
        }
    }

    /**
     * Reads metadata.
     *
     * @param rawPath spool raw message path
     * @return parsed metadata, or null when missing/parse-failed
     */
    override fun readMeta(rawPath: Path): SpoolMetadata? = runCatching {
        val jsonRaw = redisTemplate.opsForValue().get(metaKey(rawPath)) ?: return null
        SpoolMetadataJsonCodec.fromJson(rawPath, jsonRaw)
    }.getOrElse { e ->
        redisMetadataLog.warn(e) { "Failed to parse spool metadata from Redis: ${rawPath}" }
        null
    }

    /**
     * Removes raw payload and metadata together for a spool message.
     *
     * @param rawPath raw path to remove
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
     * Materializes Redis raw payload into a temporary file for delivery.
     *
     * @param rawPath spool message reference path
     * @return temporary raw file path for delivery
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
     * Cleans up temporary raw file used for delivery.
     *
     * @param preparedPath temporary raw file path to delete
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
     * Executes Redis key updates in a transaction.
     *
     * @param mutator key operations within transaction
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
