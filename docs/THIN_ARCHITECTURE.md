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
- `com.crinity.kotlinsmtp.server.SmtpServer` creates the Netty pipeline.
- Optional: PROXY protocol v1 (`HAProxyMessageDecoder`) if enabled.
- Optional: implicit TLS (SMTPS) via `SslHandler` if enabled.

2) Inbound framing
- `com.crinity.kotlinsmtp.server.SmtpInboundDecoder` turns the inbound stream into:
  - `SmtpInboundFrame.Line` (SMTP command lines)
  - `SmtpInboundFrame.Bytes` (BDAT chunks)

3) Session orchestration
- `com.crinity.kotlinsmtp.protocol.handler.SmtpChannelHandler` creates one `SmtpSession` per connection.
- It feeds inbound frames into a per-session coroutine consumer to preserve ordering.

4) SMTP state machine
- `com.crinity.kotlinsmtp.server.SmtpSession.handle()`:
  - sends greeting (220)
  - reads lines sequentially
  - dispatches via `com.crinity.kotlinsmtp.protocol.command.api.SmtpCommands.handle(line, session)`

5) Command layer
- Each command updates `SessionData` and/or calls into the transaction handler.
- DATA/BDAT eventually call `SmtpProtocolHandler.data(...)`.

6) Transaction handler (message acceptance)
- `SmtpSession.transactionHandler` is lazily created via `SmtpServer.transactionHandlerCreator`.
- Current default: `com.crinity.kotlinsmtp.protocol.handler.SimpleSmtpProtocolHandler`
  - stores raw message via `MessageStore.storeRfc822(...)`
  - either:
    - deliver synchronously, or
    - enqueue to spooler (`MailSpooler`) and return success to SMTP client

7) Delivery
- `com.crinity.kotlinsmtp.spool.MailDeliveryService`:
  - local: `LocalMailboxManager.deliverToLocalMailbox(...)`
  - external: `MailRelay.relayMessage(...)` (DNS MX lookup + Jakarta Mail transport)
- `com.crinity.kotlinsmtp.spool.MailSpooler` provides retry/backoff + optional DSN generation via `DsnService`.

### Key State Objects

- `com.crinity.kotlinsmtp.model.SessionData`:
  - greeting state (EHLO/HELO)
  - AUTH state
  - TLS state
  - MAIL/RCPT and ESMTP parameters (SIZE/SMTPUTF8/DSN)

- `com.crinity.kotlinsmtp.server.SmtpSession`:
  - owns connection lifecycle
  - owns DATA/BDAT mode and buffering
  - provides `sendResponse(...)` and `sendMultilineResponse(...)`

### Extension Points (Current)

These are the primary seams that already exist (good for library extraction):

- `com.crinity.kotlinsmtp.storage.MessageStore`
  - boundary for storing accepted raw RFC822 (usually .eml)
  - current impl: `FileMessageStore`

- `com.crinity.kotlinsmtp.auth.AuthService`
  - boundary for AUTH verification
  - current impl: `InMemoryAuthService`

- `com.crinity.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
  - per-transaction handler (MAIL/RCPT/DATA)
  - current default: `SimpleSmtpProtocolHandler`

- `com.crinity.kotlinsmtp.protocol.handler.SmtpUserHandler`
  - VRFY-like user checks / local policy
  - current impl: `LocalDirectoryUserHandler`

- `com.crinity.kotlinsmtp.protocol.handler.SmtpMailingListHandler`
  - EXPN mailing list expansion
  - current impl: `LocalFileMailingListHandler`

### Spring-Specific Layer (Wiring Only)

These classes exist only to wire and run the server via Spring Boot:

- `com.crinity.kotlinsmtp.KotlinSmtpApplication` (Spring Boot entrypoint)
- `com.crinity.kotlinsmtp.server.SmtpServerRunner` (start/stop on Spring lifecycle)
- `com.crinity.kotlinsmtp.config.SmtpServerConfig` (ConfigurationProperties + @Bean wiring)

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
  - `kotlin-smtp-app` (a runnable Spring Boot app), or
  - `kotlin-smtp-starter` (auto-configuration) + separate example app

### Risks / Couplings To Watch During Extraction

- Global registry: `AuthRegistry` (global mutable) makes multi-server tests/config harder.
- File-system defaults: Windows paths in config (`C:\smtp-server\...`) are host-specific.
- Mixed responsibilities in the default handler (`SimpleSmtpProtocolHandler` calls store + delivery + spool).
