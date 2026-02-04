## Thin Architecture (Current Codebase)

This document is a "thin" map of the current Kotlin SMTP project so we can refactor safely into a library (core) without losing behavior.

Goals:
- Capture the end-to-end runtime flow (connection -> commands -> message acceptance -> delivery).
- Identify extension points (interfaces / pluggable behavior).
- Define boundaries for the first refactor step: extracting a Spring-free `core` module.

Non-goals:
- Full RFC documentation.
- Rewriting the code or adding large amounts of comments.

### Runtime Flow (Happy Path)

1) TCP accept
- `io.github.kotlinsmtp.server.SmtpServer` creates the Netty pipeline.
- Optional: PROXY protocol v1 (`HAProxyMessageDecoder`) if enabled.
- Optional: implicit TLS (SMTPS) via `SslHandler` if enabled.

2) Inbound framing
- `io.github.kotlinsmtp.server.SmtpInboundDecoder` turns the inbound stream into:
  - `SmtpInboundFrame.Line` (SMTP command lines)
  - `SmtpInboundFrame.Bytes` (BDAT chunks)

3) Session orchestration
- `io.github.kotlinsmtp.protocol.handler.SmtpChannelHandler` creates one `SmtpSession` per connection.
- Before session start (PROXY/implicit TLS gating), it buffers only a small number of line frames.
- After session start, it enqueues inbound frames directly into the session via `SmtpSession.tryEnqueueInboundFrame(...)`.
- Inbound flow-control is handled by `SmtpBackpressureController` (autoRead throttling) and inflight BDAT caps.

4) SMTP state machine
- `io.github.kotlinsmtp.server.SmtpSession.handle()`:
  - sends greeting (220)
  - reads lines sequentially
  - dispatches via `io.github.kotlinsmtp.protocol.command.api.SmtpCommands.handle(line, session)`

STARTTLS upgrade flow (notable)
- `StartTlsCommand` triggers a guarded upgrade:
  - pipelining is rejected
  - a temporary inbound gate buffers raw bytes until `SslHandler` is installed
  - handshake is awaited before session state is reset
  - implementation is isolated in `io.github.kotlinsmtp.server.SmtpTlsUpgradeManager`

5) Command layer
- Each command updates `SessionData` and/or calls into the transaction handler.
- DATA/BDAT eventually call `SmtpProtocolHandler.data(...)`.

6) Transaction handler (message acceptance)
- `SmtpSession.transactionHandler` is lazily created via `SmtpServer.transactionHandlerCreator`.
- Current default: `io.github.kotlinsmtp.protocol.handler.SimpleSmtpProtocolHandler`
  - stores raw message via `MessageStore.storeRfc822(...)`
  - either:
    - deliver synchronously, or
    - enqueue to spooler (`MailSpooler`) and return success to SMTP client

7) Delivery
- `io.github.kotlinsmtp.spool.MailDeliveryService`:
  - local: `LocalMailboxManager.deliverToLocalMailbox(...)`
  - external: `MailRelay.relayMessage(...)` (DNS MX lookup + Jakarta Mail transport)
- `io.github.kotlinsmtp.spool.MailSpooler` provides retry/backoff + optional DSN generation via `DsnService`.

### Key State Objects

- `io.github.kotlinsmtp.model.SessionData`:
  - greeting state (EHLO/HELO)
  - AUTH state
  - TLS state
  - MAIL/RCPT and ESMTP parameters (SIZE/SMTPUTF8/DSN)

- `io.github.kotlinsmtp.server.SmtpSession`:
  - owns connection lifecycle
  - owns DATA/BDAT mode and buffering
  - provides `sendResponse(...)` and `sendMultilineResponse(...)`

### Extension Points (Current)

These are the primary seams that already exist (good for library extraction):

- `io.github.kotlinsmtp.storage.MessageStore`
  - boundary for storing accepted raw RFC822 (usually .eml)
  - current impl: `FileMessageStore`

- `io.github.kotlinsmtp.auth.AuthService`
  - boundary for AUTH verification
  - current impl: `InMemoryAuthService`

- `io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
  - per-transaction handler (MAIL/RCPT/DATA)
  - current default: `SimpleSmtpProtocolHandler`

- `io.github.kotlinsmtp.protocol.handler.SmtpUserHandler`
  - VRFY-like user checks / local policy
  - current impl: `LocalDirectoryUserHandler`

- `io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler`
  - EXPN mailing list expansion
  - current impl: `LocalFileMailingListHandler`

### Spring-Specific Layer (Wiring Only)

These classes exist only to wire and run the server via Spring Boot:

- `io.github.kotlinsmtp.server.SmtpServerRunner` (start/stop on Spring lifecycle)
- `io.github.kotlinsmtp.config.KotlinSmtpAutoConfiguration` (auto-config wiring)
- `io.github.kotlinsmtp.config.SmtpServerProperties` (ConfigurationProperties)

Note: a runnable Spring Boot app (portfolio/demo) is intentionally deferred until the library boundary and public API are stable.

Important: extracting a Spring-free `core` module does NOT mean "the server cannot start".
It means:
- `core` provides the SMTP engine types (SmtpServer/SmtpSession/etc.) and can be instantiated by any host.
- a separate host module (Spring Boot app or starter) creates/wires the objects and calls `server.start()`.

### What We Want After Refactor (Target Boundaries)

Minimum viable library split:

- `kotlin-smtp-core`
  - Netty SMTP engine + protocol state machine
  - public interfaces (MessageStore, AuthService, SmtpProtocolHandler, ...)
  - NO Spring dependencies

- host module (choose one now, add others later):
  - `kotlin-smtp-spring-boot-starter` (auto-configuration)
  - `kotlin-smtp-example-app` (separate, last)

### Risks / Couplings To Watch During Extraction

- Global registry: `AuthRegistry` (global mutable) makes multi-server tests/config harder.
- File-system defaults: Windows paths in config (`C:\smtp-server\...`) are host-specific.
- Mixed responsibilities in the default handler (`SimpleSmtpProtocolHandler` calls store + delivery + spool).
