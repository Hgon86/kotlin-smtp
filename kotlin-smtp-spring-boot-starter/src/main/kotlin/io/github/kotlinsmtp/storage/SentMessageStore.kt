package io.github.kotlinsmtp.storage

import java.nio.file.Path

/**
 * 발신(보낸 메일함) 원문 저장 경계입니다.
 */
interface SentMessageStore {
    /**
     * 발신 메시지를 보낸 메일함 저장소에 기록합니다.
     *
     * @param rawPath 원문 RFC822 파일 경로
     * @param envelopeSender envelope sender
     * @param submittingUser 인증된 제출 사용자 식별자
     * @param recipients 수신자 목록
     * @param messageId 메시지 식별자
     * @param authenticated 인증 세션 여부
     */
    fun archiveSubmittedMessage(
        rawPath: Path,
        envelopeSender: String?,
        submittingUser: String?,
        recipients: List<String>,
        messageId: String,
        authenticated: Boolean,
    )
}
