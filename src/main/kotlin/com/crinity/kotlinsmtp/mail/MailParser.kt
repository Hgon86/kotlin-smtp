package com.crinity.kotlinsmtp.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*

private val log = KotlinLogging.logger {}

/**
 * 이메일 데이터를 파싱하고 MimeMessage 객체로 변환하는 클래스
 */
class MailParser {

    /**
     * 입력 스트림에서 MimeMessage 객체를 생성합니다.
     *
     * @param inputStream 이메일 데이터 입력 스트림
     * @param sender 발신자 이메일 주소
     * @param recipients 수신자 이메일 주소 목록
     * @return 생성된 MimeMessage 객체
     */
    fun createMimeMessage(inputStream: InputStream, sender: String?, recipients: Collection<String>): MimeMessage {
        val props = Properties()
        val session = Session.getInstance(props)

        return MimeMessage(session).apply {
            // 발신자 설정
            sender?.let { setFrom(InternetAddress(it)) }

            // 기본 TO 수신자 추가
            recipients.forEach { recipient ->
                try {
                    addRecipient(Message.RecipientType.TO, InternetAddress(recipient))
                } catch (e: Exception) {
                    log.warn { "Invalid recipient address: $recipient - ${e.message}" }
                }
            }

            // 현재 시간으로 발송 시간 설정
            sentDate = Date()

            // 대용량 메일 처리를 위한 버퍼링
            val buffer = ByteArray(8192)
            val baos = ByteArrayOutputStream()

            inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    baos.write(buffer, 0, bytesRead)
                }
            }

            // 메일 데이터 분석 및 설정
            val messageData = baos.toByteArray()
            parseAndSetContent(this, messageData)
            saveChanges()
        }
    }

    /**
     * 이메일 데이터를 분석하여 MimeMessage에 내용을 설정합니다.
     *
     * @param message 설정할 MimeMessage 객체
     * @param data 이메일 원본 데이터
     */
    private fun parseAndSetContent(message: MimeMessage, data: ByteArray) {
        runCatching {
            // 메일 데이터를 문자열로 변환
            val content = String(data)

            // 헤더와 본문 분리
            val parts = content.split("\r\n\r\n", limit = 2)
            val headers = if (parts.size >= 2) parts[0] else null
            val body = if (parts.size >= 2) parts[1] else content

            // 헤더가 없는 경우 기본 타입으로 설정

            if (headers == null) {
                message.setContent(content, "text/plain; charset=UTF-8")
                return
            }

            // CC와 BCC 헤더 처리
            processHeaderRecipients(message, headers, "Cc", Message.RecipientType.CC)
            processHeaderRecipients(message, headers, "Bcc", Message.RecipientType.BCC)

            // Content-Type 헤더 찾기
            val contentTypeRegex = "(?i)Content-Type:\\s*([^;\\r\\n]+)(?:;\\s*charset=([^;\\r\\n]+))?".toRegex()

            // 헤더에서 Content-Type 추출 또는 내용 기반 추측
            contentTypeRegex.find(headers)?.let { match ->
                val mimeType = match.groupValues[1].trim()
                val charset = match.groupValues.getOrNull(2)?.trim() ?: "UTF-8"

                when {
                    // 멀티파트 메시지 처리
                    mimeType.startsWith("multipart/") -> {
                        ByteArrayInputStream(data).use { inputStream ->
                            val tempSession = Session.getInstance(Properties())
                            val tempMessage = MimeMessage(tempSession, inputStream)
                            message.setContent(tempMessage.content, tempMessage.contentType)
                        }
                    }
                    // 일반 텍스트/HTML 메시지
                    else -> message.setContent(body, "$mimeType; charset=$charset")
                }
            } ?: run {
                // Content-Type 헤더가 없는 경우 내용 기반으로 추측
                val contentType = detectContentType(body)
                message.setContent(body, "$contentType; charset=UTF-8")
            }

            // 다른 중요 헤더들 복사
            copyHeaders(message, headers)

        }.onFailure { e ->
            log.warn(e) { "Error parsing email content, using default content type" }
            message.setContent(data, "text/plain; charset=UTF-8")
        }
    }

    /**
     * 헤더에서 특정 타입의 수신자를 추출하여 메시지에 추가합니다.
     */
    private fun processHeaderRecipients(
        message: MimeMessage,
        headers: String,
        headerName: String,
        recipientType: Message.RecipientType
    ) {
        val regex = "(?i)^$headerName:\\s*(.+)$".toRegex(RegexOption.MULTILINE)

        regex.find(headers)?.let { match ->
            match.groupValues[1].split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { address ->
                    try {
                        message.addRecipient(recipientType, InternetAddress(address))
                    } catch (e: Exception) {
                        log.warn { "Invalid $headerName address: $address - ${e.message}" }
                    }
                }
        }
    }

    /**
     * 본문 내용을 기반으로 Content-Type을 추측합니다.
     */
    private fun detectContentType(body: String): String {
        return when {
            body.contains("<!DOCTYPE html>", ignoreCase = true) -> "text/html"
            body.contains("<html", ignoreCase = true) -> "text/html"
            body.contains("<body", ignoreCase = true) -> "text/html"
            else -> "text/plain"
        }
    }

    /**
     * 헤더를 메시지에 복사합니다.
     */
    private fun copyHeaders(message: MimeMessage, headers: String) {
        val excludedHeaders = setOf(
            "from", "to", "cc", "bcc", "date", "content-type"
        )

        headers.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                line.indexOf(':').takeIf { it > 0 }?.let { colonIndex ->
                    val name = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    name to value
                }
            }
            .filter { (name, _) -> !excludedHeaders.contains(name.lowercase()) }
            .forEach { (name, value) -> message.addHeader(name, value) }
    }
} 