package io.github.kotlinsmtp.metrics

import io.github.kotlinsmtp.spi.SmtpMessageAcceptedEvent
import io.github.kotlinsmtp.spi.SmtpMessageEnvelope
import io.github.kotlinsmtp.spi.SmtpMessageRejectedEvent
import io.github.kotlinsmtp.spi.SmtpMessageStage
import io.github.kotlinsmtp.spi.SmtpMessageTransferMode
import io.github.kotlinsmtp.spi.SmtpSessionContext
import io.github.kotlinsmtp.spi.SmtpSessionEndReason
import io.github.kotlinsmtp.spi.SmtpSessionEndedEvent
import io.github.kotlinsmtp.spi.SmtpSessionStartedEvent
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicrometerSmtpEventHookTest {

    /**
     * Verifies that session/message events update expected counters and gauge.
     */
    @Test
    fun `event hook updates session and message metrics`() = runBlocking {
        val registry = SimpleMeterRegistry()
        val hook = MicrometerSmtpEventHook(registry)
        val context = SmtpSessionContext(
            sessionId = "s1",
            peerAddress = "127.0.0.1",
            serverHostname = "localhost",
            helo = "test.client",
            tlsActive = false,
            authenticated = false,
        )
        val envelope = SmtpMessageEnvelope(
            mailFrom = "sender@test.com",
            rcptTo = listOf("recipient@test.com"),
            dsnEnvid = null,
            dsnRet = null,
            rcptDsn = emptyMap(),
        )

        hook.onSessionStarted(SmtpSessionStartedEvent(context))
        hook.onMessageAccepted(
            SmtpMessageAcceptedEvent(
                context = context,
                envelope = envelope,
                transferMode = SmtpMessageTransferMode.DATA,
                sizeBytes = 128,
            )
        )
        hook.onMessageRejected(
            SmtpMessageRejectedEvent(
                context = context,
                envelope = envelope,
                transferMode = SmtpMessageTransferMode.DATA,
                stage = SmtpMessageStage.PROCESSING,
                responseCode = 550,
                responseMessage = "5.7.1 rejected",
            )
        )
        hook.onSessionEnded(SmtpSessionEndedEvent(context, SmtpSessionEndReason.CLIENT_QUIT))

        assertEquals(1.0, registry.get("smtp.sessions.started.total").counter().count())
        assertEquals(1.0, registry.get("smtp.sessions.ended.total").counter().count())
        assertEquals(1.0, registry.get("smtp.messages.accepted.total").counter().count())
        assertEquals(1.0, registry.get("smtp.messages.rejected.total").counter().count())
        assertEquals(0.0, registry.get("smtp.connections.active").gauge().value())
    }
}
