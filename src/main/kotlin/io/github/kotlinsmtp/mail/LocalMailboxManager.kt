package io.github.kotlinsmtp.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

class LocalMailboxManager(
    private val mailboxDir: Path = Path.of("C:\\smtp-server\\mailboxes")
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val mailboxRoot: Path = mailboxDir.toAbsolutePath().normalize()

    init {
        try {
            Files.createDirectories(mailboxRoot)
            log.info { "Initialized mailbox directory at: $mailboxRoot" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create mailbox directory: $mailboxRoot" }
            throw RuntimeException("Failed to initialize mailbox directory", e)
        }
    }

    suspend fun deliverToLocalMailbox(username: String, tempFile: Path): Path = withContext(Dispatchers.IO) {
        // TODO(storage): 로컬 파일 저장 대신 DB/S3/오브젝트 스토리지로 교체 가능하도록 추상화(MessageStore) 도입
        //               - 대량 트래픽/다중 인스턴스 환경에서는 로컬 디스크는 한계가 큼
        //               - 최종 목표: "메일 본문 저장"과 "SMTP 수신/릴레이"를 느슨하게 분리
        val sanitizedUsername = sanitizeUsername(username)
        val userMailbox = getUserMailboxDir(username)

        val timestamp = LocalDateTime.now().format(dateFormatter)
        val mailFile = userMailbox.resolve("mail_${timestamp}.eml")

        try {
            Files.copy(tempFile, mailFile, StandardCopyOption.REPLACE_EXISTING)
            log.info { "Mail delivered to $sanitizedUsername's mailbox: ${mailFile.fileName}" }
            mailFile
        } catch (e: Exception) {
            log.error(e) { "Failed to deliver mail to $sanitizedUsername's mailbox" }
            throw e
        }
    }

    private fun getUserMailboxDir(username: String): Path {
        val sanitizedUsername = sanitizeUsername(username)
        val userMailbox = mailboxRoot.resolve(sanitizedUsername).normalize()
        if (!userMailbox.startsWith(mailboxRoot)) {
            throw IllegalArgumentException("Mailbox path escape attempt")
        }
        try {
            Files.createDirectories(userMailbox)
            log.debug { "Ensured mailbox directory for user: $sanitizedUsername" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create mailbox directory for user: $sanitizedUsername" }
            throw RuntimeException("Failed to create user mailbox directory", e)
        }
        return userMailbox
    }

    private fun sanitizeUsername(username: String): String {
        val u = username.trim()
        if (u.isEmpty()) throw IllegalArgumentException("Username is blank")

        // 파일시스템 경로로 쓰일 수 있으므로 매우 보수적으로 제한합니다.
        // - 점(.)은 허용하지만 "."/".." 자체는 금지(정규화로 루트 탈출 가능)
        val sanitized = u.map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '+' -> ch
                else -> '_'
            }
        }.joinToString("").take(128)

        if (sanitized == "." || sanitized == "..") {
            throw IllegalArgumentException("Invalid username")
        }
        if (sanitized.isBlank()) throw IllegalArgumentException("Invalid username")
        return sanitized
    }
}
