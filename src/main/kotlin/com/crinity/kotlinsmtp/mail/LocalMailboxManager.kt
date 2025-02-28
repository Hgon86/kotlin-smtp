package com.crinity.kotlinsmtp.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * 사용자별 메일박스 디렉토리를 관리하고 메일을 저장 AI 작성 버전
 * 추후 카프카 이벤트 발행으로 변경 예정
 */
class LocalMailboxManager(
    private val mailboxDir: Path = Path.of("C:\\smtp-server\\mailboxes")
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    init {
        try {
            Files.createDirectories(mailboxDir)
            log.info { "Initialized mailbox directory at: $mailboxDir" }
        } catch (e: Exception) {
            log.error(e) { "Failed to create mailbox directory: $mailboxDir" }
            throw RuntimeException("Failed to initialize mailbox directory", e)
        }
    }

    /**
     * 로컬 사용자의 메일박스에 메일을 저장합니다.
     *
     * @param username 사용자 이름 (이메일 주소의 @ 앞 부분)
     * @param tempFile 임시 저장된 메일 파일
     * @return 저장된 메일 파일 경로
     */
    suspend fun deliverToLocalMailbox(username: String, tempFile: Path): Path = withContext(Dispatchers.IO) {
        // 사용자명 유효성 검사
        val sanitizedUsername = sanitizeUsername(username)
        val userMailbox = getUserMailboxDir(sanitizedUsername)

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

    /**
     * 사용자 메일박스 디렉토리를 반환합니다.
     * 디렉토리가 없으면 생성합니다.
     *
     * @param username 사용자 이름
     * @return 사용자 메일박스 디렉토리 경로
     */
    private fun getUserMailboxDir(username: String): Path {
        val sanitizedUsername = sanitizeUsername(username)
        val userMailbox = mailboxDir.resolve(sanitizedUsername)

        if (!Files.exists(userMailbox)) {
            try {
                Files.createDirectories(userMailbox)
                log.debug { "Created mailbox directory for user: $sanitizedUsername" }
            } catch (e: Exception) {
                log.error(e) { "Failed to create mailbox directory for user: $sanitizedUsername" }
                throw RuntimeException("Failed to create user mailbox directory", e)
            }
        }

        return userMailbox
    }

    /**
     * 사용자 이름에서 파일 시스템에 유효하지 않은 문자를 제거합니다.
     */
    private fun sanitizeUsername(username: String): String {
        // 파일 시스템에 유효하지 않은 문자 제거
        return username.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
} 