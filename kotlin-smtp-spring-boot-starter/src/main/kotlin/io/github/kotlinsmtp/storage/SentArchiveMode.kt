package io.github.kotlinsmtp.storage

/**
 * 보낸 메일함 저장 트리거 정책입니다.
 */
enum class SentArchiveMode {
    /** 저장 기능을 사용하지 않습니다. */
    DISABLED,

    /** AUTH 인증 세션에서 제출된 메일만 저장합니다. */
    AUTHENTICATED_ONLY,

    /** AUTH 인증 또는 외부 릴레이 제출(정책 통과) 메일을 저장합니다. */
    TRUSTED_SUBMISSION,
}
