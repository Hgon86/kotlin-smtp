package io.github.kotlinsmtp.spool

import java.nio.file.Path
import java.util.Base64

/**
 * Utility for generating Redis spool keys.
 */
internal object RedisSpoolKeyCodec {
    /**
     * Encodes a spool reference path into a Redis key segment.
     *
     * @param spoolReferencePath spool reference path
     * @return URL-safe Base64 token
     */
    fun pathToken(spoolReferencePath: Path): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(spoolReferencePath.toString().toByteArray(Charsets.UTF_8))
}
