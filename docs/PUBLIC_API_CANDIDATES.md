## Public API Candidates (Library Mode)

This file lists the intended semver-stable API surface for publishing `kotlin-smtp-core` as a reusable library.

Rule of thumb:
- If a host application must **call / implement / reference** a type to customize behavior, it belongs in the public API.
- Everything else remains internal or implementation detail.

### Semver-Stable Packages (Target)

We treat only the following packages as semver-stable API:

- `io.github.kotlinsmtp.server` (selected types only)
- `io.github.kotlinsmtp.model`
- `io.github.kotlinsmtp.exception`
- `io.github.kotlinsmtp.storage`
- `io.github.kotlinsmtp.auth`
- `io.github.kotlinsmtp.protocol.handler`
- `io.github.kotlinsmtp.spi`

### Core Engine (Public)

- `io.github.kotlinsmtp.server.SmtpServer`
  - preferred entrypoint: `SmtpServer.create(...)` / `SmtpServer.builder(...)`
  - keep the implementation constructor `internal`
- `io.github.kotlinsmtp.server.SmtpSpooler`
  - minimal hook to trigger host-side delivery/spool processing

### Core Models (Public)

- `io.github.kotlinsmtp.model.SessionData`
  - engine-owned session/transaction state (read-mostly from host code)
- `io.github.kotlinsmtp.model.SmtpUser`
  - returned by `SmtpUserHandler`
- `io.github.kotlinsmtp.model.RcptDsn`
  - DSN-related RCPT parameters surfaced through `SessionData`

### Core Exceptions (Public)

- `io.github.kotlinsmtp.exception.SmtpSendResponse`
  - host/starter handlers may throw this to return a specific SMTP status code/message

### Extension Interfaces (Public)

- `io.github.kotlinsmtp.storage.MessageStore`
- `io.github.kotlinsmtp.auth.AuthService`
- `io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
- `io.github.kotlinsmtp.protocol.handler.SmtpUserHandler`
- `io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler`

### SPI Hooks (Public)

- `io.github.kotlinsmtp.spi.SmtpEventHook`
  - minimal non-fatal event hooks for external integrations (S3/Kafka/DB metadata, etc.)
- `io.github.kotlinsmtp.spi.*` event/context models

### Not Public / Internal (Examples)

- Netty pipeline + framing internals (must remain internal):
  - `io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler`
  - `io.github.kotlinsmtp.server.SmtpInboundDecoder`
  - `io.github.kotlinsmtp.server.SmtpInboundFrame`
- Protocol command implementations:
  - `io.github.kotlinsmtp.protocol.command.*`
- Utility/internal helpers:
  - `io.github.kotlinsmtp.utils.*`
  - `io.github.kotlinsmtp.server.*` (except the explicitly listed public types)

### Note: Implementations Live Outside Core

The core module is infrastructure-agnostic.

Concrete implementations such as file-based storage, local mailbox management, or outbound relay are expected to live in:

- `kotlin-smtp-spring-boot-starter` (convenience wiring + local default implementations)
- `kotlin-smtp-relay*` optional modules (outbound relay + policy + DSN)
