# Extension Recipes

This file provides minimal, practical customization examples.

## Recipe 1: Replace `MessageStore` with DB-backed storage

```kotlin
@Configuration
class CustomStorageConfig {

    @Bean
    fun messageStore(repository: MessageRepository): MessageStore {
        return object : MessageStore {
            override suspend fun storeRfc822(
                messageId: String,
                receivedHeaderValue: String,
                rawInput: InputStream,
            ): Path {
                // Persist payload + metadata in your own schema/storage
                return repository.saveRaw(messageId, receivedHeaderValue, rawInput)
            }
        }
    }
}
```

When to use:
- Store raw messages in DB/Object Storage
- Add compliance/audit metadata at ingest time

## Recipe 2: Replace `AuthService` with JDBC/LDAP

```kotlin
@Configuration
class CustomAuthConfig {

    @Bean
    fun authService(userAuth: UserAuthGateway): AuthService {
        return object : AuthService {
            override val enabled: Boolean = true
            override val required: Boolean = false

            override fun verify(username: String, password: String): Boolean {
                return userAuth.verify(username, password)
            }
        }
    }
}
```

## Recipe 3: Add relay policy gate (`RelayAccessPolicy`)

```kotlin
@Configuration
class CustomRelayPolicyConfig {

    @Bean
    fun relayAccessPolicy(): RelayAccessPolicy {
        return RelayAccessPolicy { ctx ->
            if (!ctx.authenticated) {
                return@RelayAccessPolicy RelayAccessDecision.Denied(
                    RelayDeniedReason.AUTH_REQUIRED,
                    "Authentication required for external relay"
                )
            }
            RelayAccessDecision.Allowed
        }
    }
}
```

See `docs/SECURITY_RELAY.md` for production policy guidance.

## Recipe 4: Add event publishing via `SmtpEventHook`

```kotlin
@Component
class KafkaSmtpEventHook(
    private val publisher: EventPublisher,
) : SmtpEventHook {

    override suspend fun onMessageAccepted(event: SmtpMessageAcceptedEvent) {
        publisher.publishAccepted(event.context.sessionId, event.envelope.mailFrom, event.envelope.rcptTo)
    }

    override suspend fun onMessageRejected(event: SmtpMessageRejectedEvent) {
        publisher.publishRejected(event.context.sessionId, event.responseCode, event.responseMessage)
    }
}
```

Guideline:
- Keep hook work short; publish to queue and return quickly.

## Recipe 5: Replace local-domain decision (`InboundRoutingPolicy`)

```kotlin
@Configuration
class CustomRoutingPolicyConfig {

    @Bean
    fun inboundRoutingPolicy(domainService: DomainService): InboundRoutingPolicy {
        return InboundRoutingPolicy { recipient ->
            val domain = recipient.substringAfterLast('@', "").lowercase()
            domainService.isLocalDomain(domain)
        }
    }
}
```

## Recipe 6: Replace `MailRelay` transport

```kotlin
@Configuration
class CustomRelayTransportConfig {

    @Bean
    fun mailRelay(client: MyRelayClient): MailRelay {
        return object : MailRelay {
            override suspend fun relay(request: RelayRequest): RelayResult {
                client.send(
                    id = request.messageId,
                    envelopeSender = request.envelopeSender,
                    recipient = request.recipient,
                    raw = request.rfc822.openStream()
                )
                return RelayResult(remoteHost = "relay.internal", remotePort = 25)
            }
        }
    }
}
```

## Recipe 7: Replace sent mailbox archiving (`SentMessageStore`)

```kotlin
@Configuration
class CustomSentArchiveConfig {

    @Bean
    fun sentMessageStore(archive: ArchiveClient): SentMessageStore {
        return object : SentMessageStore {
            override fun archiveSubmittedMessage(
                rawPath: Path,
                envelopeSender: String?,
                submittingUser: String?,
                recipients: List<String>,
                messageId: String,
                authenticated: Boolean,
            ) {
                archive.archive(
                    path = rawPath,
                    envelopeSender = envelopeSender,
                    submittingUser = submittingUser,
                    recipients = recipients,
                    messageId = messageId,
                    authenticated = authenticated
                )
            }
        }
    }
}
```

## Recipe 8: Replace spool backend pieces

Use this only if file/redis defaults are not enough.

- Implement `SpoolMetadataStore`
- Implement `SpoolLockManager`
- Register both beans

Tip:
- Keep lock semantics strict and idempotent.
- Preserve retry metadata integrity.

## Recipe 9: Add anti-spam decision gate

```kotlin
@Configuration
class AntiSpamPolicyConfig {

    @Bean
    fun relayAccessPolicy(spamGateway: SpamGateway): RelayAccessPolicy {
        return RelayAccessPolicy { ctx ->
            val score = spamGateway.score(
                sender = ctx.sender,
                recipient = ctx.recipient,
                peerAddress = ctx.peerAddress,
            )
            if (score >= 0.9) {
                RelayAccessDecision.Denied(
                    RelayDeniedReason.POLICY,
                    "Rejected by anti-spam policy"
                )
            } else {
                RelayAccessDecision.Allowed
            }
        }
    }
}
```

Guideline:
- Keep the decision path deterministic and fast.
- Cache external score lookups when possible.

## Recipe 10: Publish message events for AV/quarantine workflow

```kotlin
@Component
class MalwareScanHook(
    private val queue: ScanQueue,
) : SmtpEventHook {

    override suspend fun onMessageAccepted(event: SmtpMessageAcceptedEvent) {
        queue.enqueue(
            messageId = event.messageId,
            sender = event.envelope.mailFrom,
            recipients = event.envelope.rcptTo,
        )
    }
}
```

Guideline:
- Trigger scan asynchronously from hooks; avoid blocking SMTP command processing.
- Make scanner outcomes idempotent (same message may be retried/replayed operationally).

## Verification checklist after customization

1. SMTP accepts and processes a basic message.
2. Local domain route works as expected.
3. External relay respects your policy.
4. Spool retry path still operates under transient failure.
5. Event hooks do not block SMTP throughput.
