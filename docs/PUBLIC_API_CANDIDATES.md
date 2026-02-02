## Public API Candidates (For Library Mode)

This list is the "likely stable" API surface if we publish this project as a reusable library.

Rule of thumb:
- If a type is used by host applications to customize behavior, it should become part of the public API.
- Everything else should remain internal/impl until we commit to long-term compatibility.

### Core Engine (Should Be Public)

- `com.crinity.kotlinsmtp.server.SmtpServer`
  - create server instance, configure ports/policies, start/stop
- `com.crinity.kotlinsmtp.server.SmtpSession`
  - session lifecycle and response helpers (careful: may remain internal if we want to hide Netty)
- `com.crinity.kotlinsmtp.model.SessionData`
  - observable state passed into handlers

### Extension Interfaces (Should Be Public)

- `com.crinity.kotlinsmtp.storage.MessageStore`
- `com.crinity.kotlinsmtp.auth.AuthService`
- `com.crinity.kotlinsmtp.protocol.handler.SmtpProtocolHandler`
- `com.crinity.kotlinsmtp.protocol.handler.SmtpUserHandler`
- `com.crinity.kotlinsmtp.protocol.handler.SmtpMailingListHandler`

### Probably NOT Public (Keep Internal Initially)

- Netty pipeline internals:
  - `SmtpChannelHandler`, `SmtpInboundDecoder`, `SmtpInboundFrame`
- Concrete implementations (until we intentionally ship them as defaults):
  - `FileMessageStore`, `InMemoryAuthService`, `LocalMailboxManager`, `MailRelay`, `MailSpooler`, `MailDeliveryService`, `DsnService`

### Compatibility Note

If we publish, we should treat "public" packages as semver-stable.
Everything else can change without major version bumps.
