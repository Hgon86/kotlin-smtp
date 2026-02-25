package io.github.kotlinsmtp

import io.github.kotlinsmtp.protocol.handler.SmtpTransactionProcessor
import java.io.InputStream

/**
 * SMTP transaction processor for tests.
 */
class TestSmtpTransactionProcessor : SmtpTransactionProcessor() {

    override suspend fun from(sender: String) {
        // Test stub: succeed.
    }

    override suspend fun to(recipient: String) {
        // Test stub: succeed.
    }

    override suspend fun data(inputStream: InputStream, size: Long) {
        // Test stub: read and discard data.
        inputStream.use { stream ->
            val buffer = ByteArray(8192)
            while (stream.read(buffer) != -1) {
                // Consume data without persisting.
            }
        }
    }

    override suspend fun done() {
        // No cleanup needed.
    }
}
