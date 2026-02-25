# Architecture Overview

## System Structure

Kotlin SMTP is designed as a modular architecture.

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ SMTP Server  │  │   Mailbox    │  │    Spool     │     │
│  │   (Core)     │  │   Store      │  │   Handler    │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
└─────────┼────────────────┼────────────────┼───────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                     Core Engine                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   Protocol   │  │   Session    │  │    Auth      │     │
│  │   Handler    │  │   Manager    │  │   Service    │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   Netty      │  │   TLS/SSL    │  │   Framing    │     │
│  │   Pipeline   │  │   Handler    │  │   (DATA/BDAT)│     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

## Module Breakdown

### 1. kotlin-smtp-core

SMTP protocol engine with no Spring dependency.

Key components:
- `SmtpServer`: Netty-based SMTP server
- `SmtpSession`: client session lifecycle
- `Protocol Handler`: SMTP command handling (`EHLO`, `MAIL`, `RCPT`, `DATA`, ...)
- `Framing`: DATA/BDAT chunk processing
- `TLS Handler`: STARTTLS and implicit TLS

### 2. kotlin-smtp-spring-boot-starter

Spring Boot auto-configuration with default implementations.

Provided features:
- Auto-configuration
- File-based `MessageStore`
- File-based spooler
- In-memory `AuthService`
- Local mailbox management

### 3. kotlin-smtp-relay* (optional)

Outbound relay support for external domains.

Composition:
- `relay`: public API boundary (`MailRelay`, `DsnSender`, ...)
- `relay-jakarta-mail`: Jakarta Mail based implementation
- `relay-spring-boot-starter`: Spring Boot integration

## Data Flow

### Incoming Mail Flow

```
1. TCP Connection Accept
   ↓
2. SMTP Handshake (EHLO/HELO)
   ↓
3. Authentication (Optional - AUTH)
   ↓
4. Envelope (MAIL FROM, RCPT TO)
   ↓
5. Message Transfer (DATA/BDAT)
   ↓
6. Storage -> MessageStore.storeRfc822()
   ↓
7. Delivery Decision
   ├─ Local Domain -> Local Mailbox
   └─ External Domain -> Spooler (relay path)
```

### Spool / Retry Flow

```
Spool Directory
   ↓
1. Scheduled Scan (every retryDelaySeconds)
   ↓
2. Load Metadata (check nextAttemptAt)
   ↓
3. Delivery Attempt
   ├─ Success -> Remove from spool
   ├─ Transient Failure -> Schedule Retry (exponential backoff)
   └─ Permanent Failure -> DSN + Remove
```

### Outbound Relay Interoperability Notes

- Null MX (`MX 0 .`) is treated as permanent failure (`5.1.10` semantics).
- Relay transport errors are classified into transient/permanent failures.
- Missing `Date` / `Message-ID` headers are supplemented automatically for outbound interoperability.
- DSN immediate generation is limited to permanent failures in synchronous relay path.

## Extension Points

### SPI (Service Provider Interface)

Core modules expose these extension interfaces:

1. `MessageStore`: message persistence
```kotlin
interface MessageStore {
    suspend fun storeRfc822(
        messageId: String,
        receivedHeaderValue: String,
        rawInput: InputStream,
    ): Path
}
```

2. `AuthService`: authentication
```kotlin
interface AuthService {
    val enabled: Boolean
    val required: Boolean
    fun verify(username: String, password: String): Boolean
}
```

3. `SmtpTransactionProcessor`: transaction handling
```kotlin
abstract class SmtpTransactionProcessor {
    lateinit var sessionData: SessionData

    open suspend fun from(sender: String) {}
    open suspend fun to(recipient: String) {}
    open suspend fun data(inputStream: InputStream, size: Long) {}
    open suspend fun done() {}
}
```

4. `SmtpCommandInterceptor`: command-stage policy chain
```kotlin
interface SmtpCommandInterceptor {
    val order: Int

    suspend fun intercept(
        stage: SmtpCommandStage,
        context: SmtpCommandInterceptorContext,
    ): SmtpCommandInterceptorAction
}
```

5. `SmtpEventHook`: event hooks
```kotlin
interface SmtpEventHook {
    suspend fun onSessionStarted(event: SmtpSessionStartedEvent) = Unit
    suspend fun onSessionEnded(event: SmtpSessionEndedEvent) = Unit
    suspend fun onMessageAccepted(event: SmtpMessageAcceptedEvent) = Unit
    suspend fun onMessageRejected(event: SmtpMessageRejectedEvent) = Unit
}
```

## Storage Implementations

### File-Based (default)

```yaml
smtp:
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
  spool:
    dir: ./data/spool
```

### DB-Based (custom implementation)

To use a DB-backed storage implementation:

1. Implement `MessageStore`
2. Register it as a Spring bean
3. Replace default `FileMessageStore`

See `EXTENSION.md` for implementation examples.

## Security

### Built-in Security Features

- Rate limiting: per-IP connection/message controls
- TLS: STARTTLS and implicit TLS
- Auth rate limiting: AUTH brute-force protection
- PROXY protocol: only trusted proxy ranges are accepted

### Open Relay Prevention

When relay is enabled:
- `requireAuthForRelay: true` by default
- `allowedSenderDomains` restricts sender domains for unauthenticated relay exceptions
- `allowedClientCidrs` restricts unauthenticated relay by client CIDR
- External relay without policy approval is denied

## Performance

### Backpressure

- Soft throttling: toggle `autoRead` based on queued bytes
- BDAT inflight cap: chunk-level memory bounds
- Connection limits: maximum concurrent sessions per IP

### Asynchronous Processing

- Netty-based non-blocking I/O
- Coroutine-based async processing
- Asynchronous spool delivery attempts
