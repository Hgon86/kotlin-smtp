## Public API Candidates (For Library Mode)

This list is the "likely stable" API surface if we publish this project as a reusable library.

Rule of thumb:
- If a type is used by host applications to customize behavior, it should become part of the public API.
- Everything else should remain internal/impl until we commit to long-term compatibility.

### Core Engine (Should Be Public)

- `io.github.kotlinsmtp.server.SmtpServer`
  - create server instance via `SmtpServer.create(...)` / `SmtpServer.builder(...)`, start/stop
  - `SmtpServer` implementation constructor should remain internal
- `io.github.kotlinsmtp.server.SmtpSpooler`
  - minimal hook for host-side spool/delivery scheduling
- `io.github.kotlinsmtp.model.SessionData`
  - observable state passed into handlers

### Core Models (Should Be Public)

- `io.github.kotlinsmtp.model.SmtpUser`
  - returned by `SmtpUserHandler`
- `io.github.kotlinsmtp.model.RcptDsn`
  - DSN-related RCPT parameters surfaced through `SessionData`

### Core Exceptions (Should Be Public)

- `io.github.kotlinsmtp.exception.SmtpSendResponse`
  - host/starter handlers may throw this to send an SMTP response with a specific status code

### Extension Interfaces (Should Be Public)

- `io.github.kotlinsmtp.storage.MessageStore`
- `io.github.kotlinsmtp.auth.AuthService`
- `io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
- `io.github.kotlinsmtp.protocol.handler.SmtpUserHandler`
- `io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler`

### SPI Hooks (Should Be Public)

- `io.github.kotlinsmtp.spi.SmtpEventHook`
  - minimal non-fatal event hooks for external integrations (S3/Kafka/DB metadata, etc.)
- `io.github.kotlinsmtp.spi.SmtpSessionContext`
- `io.github.kotlinsmtp.spi.SmtpMessageEnvelope`
- `io.github.kotlinsmtp.spi.SmtpMessageTransferMode`
- `io.github.kotlinsmtp.spi.SmtpMessageStage`
- `io.github.kotlinsmtp.spi.SmtpSessionEndReason`
- `io.github.kotlinsmtp.spi.SmtpSessionStartedEvent`
- `io.github.kotlinsmtp.spi.SmtpSessionEndedEvent`
- `io.github.kotlinsmtp.spi.SmtpMessageAcceptedEvent`
- `io.github.kotlinsmtp.spi.SmtpMessageRejectedEvent`

### Probably NOT Public (Keep Internal Initially)

- Netty pipeline internals:
  - `SmtpChannelHandler`, `SmtpInboundDecoder`, `SmtpInboundFrame`
- Concrete implementations (until we intentionally ship them as defaults):
  - `FileMessageStore`, `InMemoryAuthService`, `LocalMailboxManager`, `MailRelay`, `MailSpooler`, `MailDeliveryService`, `DsnService`

### Note: Storage / Metadata / Events

This library is intended to be infrastructure-agnostic.

- Raw EML storage (S3/Kafka/DB) and metadata persistence are expected to be provided by:
  - host applications, and/or
  - optional integration modules.

The core module should only expose minimal extension interfaces (SPI) required for these integrations.

### Compatibility Note

If we publish, we should treat "public" packages as semver-stable.
Everything else can change without major version bumps.
