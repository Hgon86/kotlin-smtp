package io.github.kotlinsmtp.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * 파일 기반 MessageStore 구현(기능 우선).
 *
 * - 현재 프로젝트는 수신 후 spooler가 다시 복사/큐잉을 수행하므로, 여기서는 "임시 저장" 역할을 합니다.
 * - TODO(storage): 최종 저장소(S3/DB)로 바뀌면 이 구현을 교체합니다.
 */
class FileMessageStore(
    private val tempDir: Path,
    private val copyBufferSize: Int = 64 * 1024,
) : MessageStore {
    override suspend fun storeRfc822(messageId: String, receivedHeaderValue: String, rawInput: InputStream): Path =
        withContext(Dispatchers.IO) {
            Files.createDirectories(tempDir)

            // 충돌 방지: 시간(epoch ms)+messageId 조합
            val targetFile = tempDir.resolve("mail_${Instant.now().toEpochMilli()}_${messageId}.eml")

            runCatching {
                BufferedOutputStream(Files.newOutputStream(targetFile)).use { out ->
                    // SMTP 헤더는 ASCII로 충분하지만, 안전하게 UTF-8로 작성합니다.
                    out.write("Received: $receivedHeaderValue\r\n".toByteArray(StandardCharsets.UTF_8))
                    rawInput.copyTo(out, copyBufferSize)
                    out.flush()
                }
            }.onFailure { e ->
                runCatching { Files.deleteIfExists(targetFile) }
                throw e
            }

            log.debug { "Stored incoming message to temp file: $targetFile" }
            targetFile
        }
}

