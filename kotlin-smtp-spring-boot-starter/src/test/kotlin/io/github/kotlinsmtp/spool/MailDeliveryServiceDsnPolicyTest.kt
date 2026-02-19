package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.mail.LocalMailboxManager
import io.github.kotlinsmtp.model.RcptDsn
import io.github.kotlinsmtp.relay.api.DsnSender
import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessDecision
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import io.github.kotlinsmtp.relay.api.RelayPermanentException
import io.github.kotlinsmtp.relay.api.RelayRequest
import io.github.kotlinsmtp.relay.api.RelayResult
import io.github.kotlinsmtp.relay.api.RelayTransientException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MailDeliveryServiceDsnPolicyTest {
    @Test
    fun `transient relay failure does not trigger immediate DSN`() {
        val dsnSender = CountingDsnSender()
        val service = createService(
            mailRelay = failingRelay { throw RelayTransientException("451 4.4.1 Temporary failure") },
            dsnSender = dsnSender,
        )

        val raw = Files.createTempFile("mail_delivery_service_transient", ".eml")
        Files.writeString(raw, "Subject: transient\r\n\r\nbody")

        assertThrows(RelayTransientException::class.java) {
            runBlocking {
                service.relayExternal(
                    envelopeSender = "sender@example.com",
                    recipient = "recipient@remote.test",
                    rawPath = raw,
                    messageId = "msg-transient",
                    authenticated = true,
                    generateDsnOnFailure = true,
                    rcptNotify = "FAILURE",
                )
            }
        }

        assertEquals(0, dsnSender.count)
    }

    @Test
    fun `permanent relay failure triggers immediate DSN`() {
        val dsnSender = CountingDsnSender()
        val service = createService(
            mailRelay = failingRelay { throw RelayPermanentException("550 5.1.1 User unknown") },
            dsnSender = dsnSender,
        )

        val raw = Files.createTempFile("mail_delivery_service_permanent", ".eml")
        Files.writeString(raw, "Subject: permanent\r\n\r\nbody")

        assertThrows(RelayPermanentException::class.java) {
            runBlocking {
                service.relayExternal(
                    envelopeSender = "sender@example.com",
                    recipient = "recipient@remote.test",
                    rawPath = raw,
                    messageId = "msg-permanent",
                    authenticated = true,
                    generateDsnOnFailure = true,
                    rcptNotify = "FAILURE",
                )
            }
        }

        assertEquals(1, dsnSender.count)
    }

    private fun createService(mailRelay: MailRelay, dsnSender: DsnSender): MailDeliveryService {
        val mailboxDir = Files.createTempDirectory("mail_delivery_service_mailbox")
        return MailDeliveryService(
            localMailboxManager = LocalMailboxManager(mailboxDir),
            mailRelay = mailRelay,
            relayAccessPolicy = RelayAccessPolicy { RelayAccessDecision.Allowed },
            dsnSenderProvider = { dsnSender },
            localDomain = "local.test",
        )
    }

    private fun failingRelay(block: suspend (RelayRequest) -> RelayResult): MailRelay = object : MailRelay {
        override suspend fun relay(request: RelayRequest): RelayResult = block(request)
    }

    private class CountingDsnSender : DsnSender {
        var count: Int = 0

        override fun sendPermanentFailure(
            sender: String?,
            failedRecipients: List<Pair<String, String>>,
            originalMessageId: String,
            originalMessagePath: java.nio.file.Path?,
            dsnEnvid: String?,
            dsnRet: String?,
            rcptDsn: Map<String, RcptDsn>,
        ) {
            count += 1
        }
    }
}
