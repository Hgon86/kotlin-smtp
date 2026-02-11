package io.github.kotlinsmtp.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

private val sentStoreLog = KotlinLogging.logger {}

/**
 * 파일 기반 보낸 메일함 저장소입니다.
 *
 * `mailboxDir/<sender>/sent/` 하위에 발신 원문을 보관합니다.
 *
 * @property mailboxDir 메일박스 루트 디렉터리
 */
class FileSentMessageStore(
    private val mailboxDir: Path,
) : SentMessageStore {
    /**
     * 발신 메시지를 보낸 메일함에 저장합니다.
     *
     * sender가 비어있으면 저장하지 않습니다.
     *
     * @param rawPath 원문 RFC822 파일 경로
     * @param envelopeSender envelope sender
     * @param submittingUser 인증된 제출 사용자 식별자
     * @param recipients 수신자 목록
     * @param messageId 메시지 식별자
     * @param authenticated 인증 세션 여부
     */
    override fun archiveSubmittedMessage(
        rawPath: Path,
        envelopeSender: String?,
        submittingUser: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
    ) {
        val owner = if (authenticated) {
            submittingUser
        } else {
            envelopeSender?.substringBeforeLast('@')
        }

        val senderMailbox = owner
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        val root = mailboxDir.toAbsolutePath().normalize()
        val userDir = root.resolve(sanitizeMailboxOwner(senderMailbox)).resolve("sent").normalize()
        if (!userDir.startsWith(root)) {
            throw IllegalArgumentException("Sent mailbox path escape attempt")
        }

        Files.createDirectories(userDir)
        val file = userDir.resolve("sent_${Instant.now().toEpochMilli()}_${messageId}.eml")
        Files.copy(rawPath, file, StandardCopyOption.REPLACE_EXISTING)
        sentStoreLog.info {
            "Archived submitted message: sender=$senderMailbox recipients=${recipients.size} messageId=$messageId file=${file.fileName}"
        }
    }

    /**
     * 보낸 메일함 소유자 식별자를 파일시스템 안전 문자열로 정규화합니다.
     *
     * @param owner 원본 소유자 식별자
     * @return 파일시스템 안전 문자열
     */
    private fun sanitizeMailboxOwner(owner: String): String {
        val sanitized = owner.map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '+' -> ch
                else -> '_'
            }
        }.joinToString("").take(128)

        require(sanitized.isNotBlank()) { "Invalid sent mailbox owner" }
        require(sanitized != "." && sanitized != "..") { "Invalid sent mailbox owner" }
        return sanitized
    }
}
