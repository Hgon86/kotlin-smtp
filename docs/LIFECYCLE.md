# Runtime Lifecycle

This document explains where each extension point is executed.

## 1) Inbound SMTP transaction lifecycle

1. Connection accepted
2. Greeting + EHLO/HELO
3. Optional AUTH
4. MAIL FROM
5. RCPT TO (one or many)
6. DATA/BDAT receives payload
7. `SmtpProtocolHandler.data(...)`
8. Message persistence (`MessageStore.storeRfc822(...)`)
9. Routing decision (`InboundRoutingPolicy.isLocalDomain(...)`)
10. Local delivery or relay enqueue
11. Event hooks (`SmtpEventHook`) for accepted/rejected result

## 2) Relay and spool lifecycle

For non-local recipients:

1. Relay policy evaluation (`RelayAccessPolicy.evaluate(...)`)
2. Route resolution (`RelayRouteResolver.resolve(...)`)
3. Outbound send (`MailRelay.relay(...)`)
4. On transient failure: enqueue/retry in spool
5. On permanent failure: DSN path (`DsnSender` + `DsnStore`)

## 3) Hook timing guidance

- `SmtpEventHook.onSessionStarted`: after session start
- `SmtpEventHook.onMessageAccepted`: after successful transaction completion
- `SmtpEventHook.onMessageRejected`: after 4xx/5xx rejection path
- `SmtpEventHook.onSessionEnded`: near session termination

## 4) Failure behavior principles

- Event hook failures are treated as non-fatal to SMTP serving.
- Policy failures should be explicit and deterministic.
- Storage/relay failures should be categorized as transient vs permanent.

## 5) Practical placement guide

- Need to block/allow relay: `RelayAccessPolicy`
- Need custom transport path: `MailRelay`
- Need custom persistence: `MessageStore` / `SentMessageStore`
- Need observability/integration: `SmtpEventHook`
- Need local domain rules: `InboundRoutingPolicy`
