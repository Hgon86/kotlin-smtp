package io.github.kotlinsmtp.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

private val log = KotlinLogging.logger {}

class MailParser {
    private val sessionProps = Properties().apply {
        // SMTPUTF8(UTF-8 헤더) 수용을 위해 Jakarta Mail 파서에 힌트를 줍니다.
        // NOTE: 구현체/버전에 따라 동작이 다를 수 있어, 필요 시 운영에서 조정할 수 있도록 설정화할 여지가 있습니다.
        // TODO: 공식 문서/버전별 동작을 확인해 최소/권장 설정을 확정
        this["mail.mime.allowutf8"] = "true"
    }

    // 헤더 값에 포함될 수 있는 개행을 제거해 안전하게 이용하기 위한 헬퍼 메서드
    private fun sanitizeHeaderValue(value: String?): String? = value
        ?.replace(Regex("[\r\n]+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun buildReceivedValue(
        byServer: String?,
        fromPeer: String?,
        withInfo: String?,
        forRecipient: String? = null,
        idValue: String? = null,
    ): String {
        val dateStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
        val byPart = sanitizeHeaderValue(byServer)?.let { "by $it" }
        val fromPart = sanitizeHeaderValue(fromPeer)?.let { "from $it" }
        val withPart = sanitizeHeaderValue(withInfo)?.let { "with $it" }
        val forPart = sanitizeHeaderValue(forRecipient)?.let { "for <$it>" }
        val idPart = sanitizeHeaderValue(idValue)?.let { "id $it" }
        return listOfNotNull(fromPart, byPart, idPart, withPart, forPart, "; $dateStr").joinToString(" ")
    }

    fun loadMimeMessage(path: Path, sender: String?, recipients: Collection<String>): MimeMessage {
        val session = Session.getInstance(sessionProps)
        val message = Files.newInputStream(path).use { input ->
            MimeMessage(session, input)
        }

        try {
            if (message.from == null || message.from.isEmpty()) {
                sender?.let { message.setFrom(InternetAddress(it)) }
            }
            if (message.getRecipients(Message.RecipientType.TO).isNullOrEmpty()) {
                recipients.forEach { rcpt ->
                    runCatching { message.addRecipient(Message.RecipientType.TO, InternetAddress(rcpt)) }
                        .onFailure { e -> log.warn { "Invalid recipient address: $rcpt - ${e.message}" } }
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to normalise message headers" }
        }

        return message
    }
}
