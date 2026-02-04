package io.github.kotlinsmtp.storage

import java.io.InputStream
import java.nio.file.Path

/**
 * SMTP가 수신한 메시지 원문(RFC 5322; 흔히 .eml)을 저장하는 경계.
 *
 * - DB/S3 등 최종 저장소는 아직 미정이므로, 지금은 파일 기반 구현만 제공합니다.
 * - TODO(storage): 운영/확장 시에는 S3/DB로 교체할 수 있도록 이 인터페이스를 유지합니다.
 */
public interface MessageStore {
    /**
     * SMTP 본문(rawInput)을 "Received 헤더 + 원문" 형태로 저장합니다.
     *
     * @param messageId 서버 내부 트랜잭션 식별자(로그/추적용)
     * @param receivedHeaderValue Received: 헤더 값(헤더명 제외)
     * @param rawInput DATA/BDAT로 들어온 본문 스트림(바이트 보존)
     */
    public suspend fun storeRfc822(
        messageId: String,
        receivedHeaderValue: String,
        rawInput: InputStream,
    ): Path
}
