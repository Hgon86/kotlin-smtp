package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.mail.LocalMailboxManager
import io.github.kotlinsmtp.relay.api.MailRelay
import io.github.kotlinsmtp.relay.api.RelayAccessDecision
import io.github.kotlinsmtp.relay.api.RelayAccessPolicy
import io.github.kotlinsmtp.relay.api.RelayRequest
import io.github.kotlinsmtp.relay.api.RelayResult
import io.github.kotlinsmtp.relay.api.RelayTransientException
import io.github.kotlinsmtp.server.SpoolTriggerResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class MailSpoolerRetryBehaviorTest {

    @TempDir
    lateinit var tempDir: Path

    private val spoolers = mutableListOf<MailSpooler>()

    @AfterEach
    fun tearDown() {
        spoolers.forEach { it.shutdown() }
    }

    @Test
    fun `full-run transient failure increments attempt and schedules retry`() {
        val relayCalls = AtomicInteger(0)
        val deliveryService = createDeliveryService(
            mailRelay = object : MailRelay {
                override suspend fun relay(request: RelayRequest): RelayResult {
                    relayCalls.incrementAndGet()
                    throw RelayTransientException("451 4.4.1 temporary")
                }
            },
        )

        val spoolDir = tempDir.resolve("spool-full")
        val metadataStore = FileSpoolMetadataStore(spoolDir)
        val spooler = createSpooler(spoolDir, metadataStore, deliveryService)

        val raw = tempDir.resolve("full-run.eml")
        Files.writeString(raw, "Subject: full\r\n\r\nbody")
        val meta = spooler.enqueue(
            rawMessagePath = raw,
            sender = "sender@example.com",
            recipients = listOf("user@remote.test"),
            messageId = "msg-full",
            authenticated = true,
        )

        val before = Instant.now()
        spooler.tryTriggerOnce()

        awaitAtMost {
            metadataStore.readMeta(meta.rawPath)?.attempt == 1
        }

        val updated = metadataStore.readMeta(meta.rawPath)
        assertThat(updated).isNotNull
        assertThat(updated!!.attempt).isEqualTo(1)
        assertThat(updated.nextAttemptAt).isAfter(before)
        assertThat(relayCalls.get()).isEqualTo(1)
    }

    @Test
    fun `domain-targeted partial run does not increase global retry attempt`() {
        val relayCalls = AtomicInteger(0)
        val deliveryService = createDeliveryService(
            mailRelay = object : MailRelay {
                override suspend fun relay(request: RelayRequest): RelayResult {
                    relayCalls.incrementAndGet()
                    throw RelayTransientException("451 4.4.1 temporary")
                }
            },
        )

        val spoolDir = tempDir.resolve("spool-partial")
        val metadataStore = FileSpoolMetadataStore(spoolDir)
        val spooler = createSpooler(spoolDir, metadataStore, deliveryService)

        val raw = tempDir.resolve("partial-run.eml")
        Files.writeString(raw, "Subject: partial\r\n\r\nbody")
        val meta = spooler.enqueue(
            rawMessagePath = raw,
            sender = "sender@example.com",
            recipients = listOf("a@alpha.test", "b@beta.test"),
            messageId = "msg-partial",
            authenticated = true,
        )

        spooler.tryTriggerOnce("alpha.test")

        awaitAtMost {
            relayCalls.get() >= 1
        }

        val updated = metadataStore.readMeta(meta.rawPath)
        assertThat(updated).isNotNull
        assertThat(updated!!.attempt).isEqualTo(0)
        assertThat(updated.recipients).containsExactlyInAnyOrder("a@alpha.test", "b@beta.test")
    }

    @Test
    fun `duplicate trigger requests do not redeliver already completed message`() {
        val relayCalls = AtomicInteger(0)
        val deliveryService = createDeliveryService(
            mailRelay = object : MailRelay {
                override suspend fun relay(request: RelayRequest): RelayResult {
                    relayCalls.incrementAndGet()
                    return RelayResult()
                }
            },
        )

        val spoolDir = tempDir.resolve("spool-dedup")
        val metadataStore = FileSpoolMetadataStore(spoolDir)
        val spooler = createSpooler(spoolDir, metadataStore, deliveryService)

        val raw = tempDir.resolve("dedup-run.eml")
        Files.writeString(raw, "Subject: dedup\r\n\r\nbody")
        val meta = spooler.enqueue(
            rawMessagePath = raw,
            sender = "sender@example.com",
            recipients = listOf("once@remote.test"),
            messageId = "msg-dedup",
            authenticated = true,
        )

        spooler.tryTriggerOnce()
        spooler.tryTriggerOnce()

        awaitAtMost {
            metadataStore.readMeta(meta.rawPath) == null
        }

        assertThat(relayCalls.get()).isEqualTo(1)
    }

    @Test
    fun `external triggers are throttled during cooldown window`() {
        val deliveryService = createDeliveryService(
            mailRelay = object : MailRelay {
                override suspend fun relay(request: RelayRequest): RelayResult = RelayResult()
            },
        )

        val spoolDir = tempDir.resolve("spool-cooldown")
        val metadataStore = FileSpoolMetadataStore(spoolDir)
        val spooler = createSpooler(
            spoolDir = spoolDir,
            metadataStore = metadataStore,
            deliveryService = deliveryService,
            triggerCooldownMillis = 60_000,
        )

        val first = spooler.tryTriggerOnce()
        val second = spooler.tryTriggerOnce()

        assertThat(first).isEqualTo(SpoolTriggerResult.ACCEPTED)
        assertThat(second).isEqualTo(SpoolTriggerResult.UNAVAILABLE)
    }

    private fun createSpooler(
        spoolDir: Path,
        metadataStore: SpoolMetadataStore,
        deliveryService: MailDeliveryService,
        triggerCooldownMillis: Long = 0L,
    ): MailSpooler {
        val spooler = MailSpooler(
            spoolDir = spoolDir,
            maxRetries = 5,
            retryDelaySeconds = 60,
            deliveryService = deliveryService,
            dsnSenderProvider = { null },
            injectedMetadataStore = metadataStore,
            triggerCooldownMillis = triggerCooldownMillis,
        )
        spoolers.add(spooler)
        return spooler
    }

    private fun createDeliveryService(mailRelay: MailRelay): MailDeliveryService {
        val mailboxDir = Files.createDirectories(tempDir.resolve("mailbox"))
        return MailDeliveryService(
            localMailboxManager = LocalMailboxManager(mailboxDir),
            mailRelay = mailRelay,
            relayAccessPolicy = RelayAccessPolicy { RelayAccessDecision.Allowed },
            dsnSenderProvider = { null },
            localDomain = "local.test",
        )
    }

    private fun awaitAtMost(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertThat(condition()).isTrue()
    }
}
