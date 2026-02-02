package io.github.kotlinsmtp

import io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler
import java.io.InputStream

/**
 * 테스트용 SMTP 프로토콜 핸들러
 */
class TestSmtpProtocolHandler : SmtpProtocolHandler() {
    
    override suspend fun from(sender: String) {
        // 테스트용: 성공
    }
    
    override suspend fun to(recipient: String) {
        // 테스트용: 성공
    }
    
    override suspend fun data(inputStream: InputStream, size: Long) {
        // 테스트용: 데이터를 읽어서 버림
        inputStream.use { stream ->
            val buffer = ByteArray(8192)
            while (stream.read(buffer) != -1) {
                // 데이터 소비만 하고 저장하지 않음
            }
        }
    }
    
    override suspend fun done() {
        // 클린업 필요 없음
    }
}
