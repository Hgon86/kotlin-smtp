package io.github.kotlinsmtp.spool

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpoolMetadataJsonCodecTest {

    /**
     * peerAddress를 포함한 메타데이터가 JSON 라운드트립 후에도 유지되어야 합니다.
     */
    @Test
    fun `round trip preserves peer address`() {
        val metadata = SpoolMetadata(
            id = "id1",
            rawPath = Path.of("msg-1.eml"),
            sender = "alice@example.com",
            recipients = mutableListOf("bob@external.test"),
            messageId = "m1",
            authenticated = true,
            peerAddress = "10.0.0.12:587",
        )

        val encoded = SpoolMetadataJsonCodec.toJson(metadata)
        val decoded = SpoolMetadataJsonCodec.fromJson(Path.of("msg-1.eml"), encoded)

        assertThat(decoded.peerAddress).isEqualTo("10.0.0.12:587")
        assertThat(decoded.messageId).isEqualTo("m1")
    }
}
