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
 * Abstraction for spool metadata stores.
 */
interface SpoolMetadataStore {
    /**
     * Prepares directory used by spool storage.
     */
    fun initializeDirectory()

    /**
     * Counts currently pending spool messages.
     *
     * @return pending message count
     */
    fun scanPendingMessageCount(): Long

    /**
     * Lists raw file references of spool messages.
     *
     * @return message file path list
     */
    fun listMessages(): List<Path>

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
     * Persists metadata.
     *
     * @param meta metadata to persist
     */
    fun writeMeta(meta: SpoolMetadata)

    /**
     * Reads metadata.
     *
     * @param rawPath spool raw message path
     * @return parsed metadata, or null when missing/parse-failed
     */
    fun readMeta(rawPath: Path): SpoolMetadata?

    /**
     * Removes raw payload and metadata together for a spool message.
     *
     * @param rawPath raw path to remove
     */
    fun removeMessage(rawPath: Path)

    /**
     * Prepares raw-message access path for delivery.
     *
     * File-based implementations return input path as-is,
     * while non-file implementations may materialize a temporary file.
     *
     * @param rawPath spool message reference path
     * @return raw file path to use for delivery
     */
    fun prepareRawMessageForDelivery(rawPath: Path): Path = rawPath

    /**
     * Cleans up prepared raw-message access path used for delivery.
     *
     * @param preparedPath path acquired from `prepareRawMessageForDelivery`
     */
    fun cleanupPreparedRawMessage(preparedPath: Path) = Unit
}

/**
 * File-based spool metadata store implementation.
 *
 * @property spoolDir spool directory
 */
class FileSpoolMetadataStore(
    private val spoolDir: Path,
) : SpoolMetadataStore {
    /**
     * Prepares spool directory.
     */
    override fun initializeDirectory() {
        Files.createDirectories(spoolDir)
    }

    /**
     * Counts currently pending spool messages.
     *
     * @return number of `*.eml` files
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
     * Lists spool message files.
     *
     * @return spool message path list
     */
    override fun listMessages(): List<Path> = spoolDir.listDirectoryEntries("*.eml")

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
     * Persists metadata.
     *
     * @param meta metadata to persist
     */
    override fun writeMeta(meta: SpoolMetadata) {
        val metaPath = metaPath(meta.rawPath)
        metaPath.writeText(SpoolMetadataJsonCodec.toJson(meta))
    }

    /**
     * Reads metadata.
     *
     * @param rawPath spool raw message path
     * @return parsed metadata, or null when missing/parse-failed
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
     * Removes raw payload and metadata together for a spool message.
     *
     * @param rawPath raw path to remove
     */
    override fun removeMessage(rawPath: Path) {
        runCatching { Files.deleteIfExists(rawPath) }
        runCatching { Files.deleteIfExists(metaPath(rawPath)) }
    }

    private fun metaPath(rawPath: Path): Path =
        rawPath.resolveSibling(rawPath.fileName.toString().replace(".eml", ".json"))
}
