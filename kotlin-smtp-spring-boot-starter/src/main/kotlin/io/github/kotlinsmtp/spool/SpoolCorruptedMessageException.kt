package io.github.kotlinsmtp.spool

/**
 * 스풀 메시지의 원문/메타 정합성이 깨졌을 때 사용하는 예외입니다.
 */
class SpoolCorruptedMessageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
