package io.github.kotlinsmtp.spool

import java.nio.file.Path
import java.util.Base64

/**
 * Redis 스풀 키 생성 유틸리티입니다.
 */
internal object RedisSpoolKeyCodec {
    /**
     * 스풀 식별 경로를 Redis 키 세그먼트로 인코딩합니다.
     *
     * @param spoolReferencePath 스풀 식별 경로
     * @return URL-safe Base64 토큰
     */
    fun pathToken(spoolReferencePath: Path): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(spoolReferencePath.toString().toByteArray(Charsets.UTF_8))
}
