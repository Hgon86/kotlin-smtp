package io.github.kotlinsmtp.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class FileMessageStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `supplements Date and Message-ID when missing`() {
        val store = FileMessageStore(tempDir)
        val raw = """
            From: sender@example.com
            To: rcpt@example.net
            Subject: hello

            body
        """.trimIndent().replace("\n", "\r\n")

        val stored = kotlinx.coroutines.runBlocking {
            store.storeRfc822(
                messageId = "msg-1",
                receivedHeaderValue = "from 127.0.0.1 by test.local; Thu, 01 Jan 1970 00:00:00 +0000",
                rawInput = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)),
            )
        }

        val text = Files.readString(stored)
        assertThat(text).contains("Date:")
        assertThat(text).contains("Message-ID:")
    }

    @Test
    fun `preserves existing Date and Message-ID`() {
        val store = FileMessageStore(tempDir)
        val raw = """
            Date: Tue, 01 Jan 2030 00:00:00 +0000
            Message-ID: <existing@test.local>
            From: sender@example.com
            To: rcpt@example.net
            Subject: hello

            body
        """.trimIndent().replace("\n", "\r\n")

        val stored = kotlinx.coroutines.runBlocking {
            store.storeRfc822(
                messageId = "msg-2",
                receivedHeaderValue = "from 127.0.0.1 by test.local; Thu, 01 Jan 1970 00:00:00 +0000",
                rawInput = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)),
            )
        }

        val text = Files.readString(stored)
        assertThat(text).contains("Message-ID: <existing@test.local>")
    }
}
