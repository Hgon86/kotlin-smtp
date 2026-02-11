package io.github.kotlinsmtp.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileSentMessageStoreTest {

    @TempDir
    lateinit var root: Path

    /**
     * 인증 세션은 envelope sender가 달라도 submittingUser 폴더에 저장해야 합니다.
     */
    @Test
    fun `authenticated submission uses submitting user mailbox`() {
        val store = FileSentMessageStore(root)
        val raw = root.resolve("raw.eml")
        Files.writeString(raw, "Subject: test\n\nbody")

        store.archiveSubmittedMessage(
            rawPath = raw,
            envelopeSender = "spoofed@example.com",
            submittingUser = "alice",
            recipients = listOf("bob@external.test"),
            messageId = "m1",
            authenticated = true,
        )

        val sentDir = root.resolve("alice").resolve("sent")
        assertThat(Files.exists(sentDir)).isTrue()
        assertThat(Files.list(sentDir).use { it.count() }).isEqualTo(1)
    }

    /**
     * 무인증 세션은 envelope sender local-part 기준으로 저장해야 합니다.
     */
    @Test
    fun `unauthenticated submission uses envelope sender mailbox`() {
        val store = FileSentMessageStore(root)
        val raw = root.resolve("raw2.eml")
        Files.writeString(raw, "Subject: test2\n\nbody")

        store.archiveSubmittedMessage(
            rawPath = raw,
            envelopeSender = "service@example.com",
            submittingUser = null,
            recipients = listOf("carol@external.test"),
            messageId = "m2",
            authenticated = false,
        )

        val sentDir = root.resolve("service").resolve("sent")
        assertThat(Files.exists(sentDir)).isTrue()
        assertThat(Files.list(sentDir).use { it.count() }).isEqualTo(1)
    }
}
