## Public API Candidates (For Library Mode)

This list is the "likely stable" API surface if we publish this project as a reusable library.

Rule of thumb:
- If a type is used by host applications to customize behavior, it should become part of the public API.
- Everything else should remain internal/impl until we commit to long-term compatibility.

### Core Engine (Should Be Public)

- `io.github.kotlinsmtp.server.SmtpServer`
  - create server instance via `SmtpServer.create(...)` / `SmtpServer.builder(...)`, start/stop
  - `SmtpServer` implementation constructor should remain internal
- `io.github.kotlinsmtp.model.SessionData`
  - observable state passed into handlers

### Extension Interfaces (Should Be Public)

- `io.github.kotlinsmtp.storage.MessageStore`
- `io.github.kotlinsmtp.auth.AuthService`
- `io.github.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
- `io.github.kotlinsmtp.protocol.handler.SmtpUserHandler`
- `io.github.kotlinsmtp.protocol.handler.SmtpMailingListHandler`
- `io.github.kotlinsmtp.server.SmtpSpooler`

### Probably NOT Public (Keep Internal Initially)

- Netty pipeline internals:
  - `SmtpChannelHandler`, `SmtpInboundDecoder`, `SmtpInboundFrame`
- Concrete implementations (until we intentionally ship them as defaults):
  - `FileMessageStore`, `InMemoryAuthService`, `LocalMailboxManager`, `MailRelay`, `MailSpooler`, `MailDeliveryService`, `DsnService`

### Compatibility Note

If we publish, we should treat "public" packages as semver-stable.
Everything else can change without major version bumps.
