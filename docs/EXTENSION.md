# Extension Guide

This guide focuses on the practical question:

"What do I implement and register to customize behavior?"

If you want a quick lookup table, scroll down to the **Extension Point Matrix** section below.

If you want copy-ready examples, go here:
- `docs/RECIPES.md`

To understand execution order and hook timing:
- `docs/LIFECYCLE.md`

For relay security hardening:
- `docs/SECURITY_RELAY.md`

## 1) How extension works in this project

Spring Boot starter modules provide default beans with `@ConditionalOnMissingBean`.
If you register your own bean of the same contract type, your implementation is used.

Main auto-configuration:
- `kotlin-smtp-spring-boot-starter/.../KotlinSmtpAutoConfiguration.kt`
- `kotlin-smtp-relay-spring-boot-starter/.../KotlinSmtpRelayAutoConfiguration.kt`

Core SPI contracts:
- `MessageStore`
- `AuthService`
- `SmtpTransactionProcessor`
- `SmtpCommandInterceptor`
- `SmtpEventHook`
- `SmtpUserHandler`
- `SmtpMailingListHandler`
- `InboundRoutingPolicy`
- Relay-related: `MailRelay`, `RelayAccessPolicy`, `DsnSender`, `RelayRouteResolver`

## 2) Minimum mental model

- **Inbound receive path**: SMTP command handling -> `SmtpTransactionProcessor` -> `MessageStore`
- **Command-stage policy chain**: `SmtpCommandInterceptor` (MAIL/RCPT/DATA_PRE)
- **Local delivery decision**: `InboundRoutingPolicy`
- **Outbound delivery**: relay + spool + retry (`MailRelay`, `MailSpooler`)
- **Policy gate**: `RelayAccessPolicy`
- **Observability/integration**: `SmtpEventHook`

For the exact order, see `docs/LIFECYCLE.md`.

## 3) Priority and override rules

- If your bean type matches a default `@ConditionalOnMissingBean`, yours wins.
- If relay starter is on classpath and `smtp.relay.enabled=true`, relay beans from relay starter are activated.
- Relay auto-configuration is ordered before base starter to avoid bean conflicts.

## 4) Most common customizations

1. Replace message persistence
- Implement `MessageStore`
- Register as `@Bean` (or `@Component`)

2. Replace authentication backend
- Implement `AuthService`
- Register custom bean

3. Add relay policy checks
- Implement `RelayAccessPolicy`
- Enforce CIDR/domain/business rules

4. Integrate events to Kafka/DB/metrics
- Implement `SmtpEventHook`
- Keep hook fast, move long work to async queue

5. Customize local domain decision
- Implement `InboundRoutingPolicy`
- Use DB/service-backed domain list if needed

## 5) What to avoid

- Doing heavy blocking work directly in command handlers without offloading
- Throwing opaque exceptions without SMTP-safe responses
- Enabling unauthenticated relay in internet-facing environments
- Keeping stale examples that do not match current interfaces

## 6) Interface references (current contracts)

Use these source files as the canonical contract definitions:

- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/storage/MessageStore.kt`
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/auth/AuthService.kt`
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/protocol/handler/SmtpTransactionProcessor.kt`
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/spi/pipeline/SmtpCommandInterceptor.kt`
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/spi/SmtpEventHook.kt`
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/routing/InboundRoutingPolicy.kt`
- `kotlin-smtp-relay/src/main/kotlin/io/github/kotlinsmtp/relay/api/MailRelay.kt`
- `kotlin-smtp-relay/src/main/kotlin/io/github/kotlinsmtp/relay/api/RelayAccessPolicy.kt`

## 7) Recommended reading order

If you are new:
1. `docs/ARCHITECTURE.md`
2. `docs/CONFIGURATION.md`
3. `docs/RECIPES.md`
4. `docs/SECURITY_RELAY.md`

If you are implementing production customization:
1. `docs/LIFECYCLE.md`
2. `docs/RECIPES.md`
3. `docs/SECURITY_RELAY.md`
# Extension Point Matrix

Use this table to quickly decide which contract to implement.

| Goal | Implement | Register As | Called In | Notes |
|---|---|---|---|---|
| Replace inbound message persistence | `MessageStore` | Bean of type `MessageStore` | Transaction processor after DATA/BDAT | Return stored path used by downstream flow |
| Replace sent-mail archive backend | `SentMessageStore` | Bean of type `SentMessageStore` | Submission path when archive policy matches | Good place for S3/DB archive |
| Replace auth backend | `AuthService` | Bean of type `AuthService` | AUTH command flow | Keep verification fast |
| Change local vs external domain decision | `InboundRoutingPolicy` | Bean of type `InboundRoutingPolicy` | Delivery decision phase | Can be DB/service backed |
| Add user lookup logic (VRFY) | `SmtpUserHandler` | Bean of type `SmtpUserHandler` | VRFY command | Return empty list if not found |
| Add EXPN list expansion logic | `SmtpMailingListHandler` | Bean of type `SmtpMailingListHandler` | EXPN command | Use policy controls in production |
| Customize transaction behavior | `SmtpTransactionProcessor` factory | `SmtpServerBuilder.useTransactionProcessorFactory` (core usage) | MAIL/RCPT/DATA hooks | Advanced use-case |
| Add command-stage policy chain | `SmtpCommandInterceptor` | Bean(s) of type `SmtpCommandInterceptor` | MAIL/RCPT/DATA pre-check | Ordered chain, can deny/drop before core handler |
| Control relay allow/deny | `RelayAccessPolicy` | Bean of type `RelayAccessPolicy` | Before outbound relay | Primary open-relay defense point |
| Replace outbound relay transport | `MailRelay` | Bean of type `MailRelay` | External delivery path | Default uses Jakarta Mail relay stack |
| Customize relay route resolution | `RelayRouteResolver` | Bean of type `RelayRouteResolver` | Per-recipient relay routing | Exact domain > wildcard > default route |
| Customize DSN generation/sending | `DsnSender` | Bean of type `DsnSender` | Failure notification flow | Use with `DsnStore` |
| Customize spool metadata backend | `SpoolMetadataStore` | Bean of type `SpoolMetadataStore` | Spool queue processing | File/Redis defaults provided |
| Customize spool locking backend | `SpoolLockManager` | Bean of type `SpoolLockManager` | Spool queue processing | File/Redis defaults provided |
| Hook events to external systems | `SmtpEventHook` | Bean(s) of type `SmtpEventHook` | Session/message lifecycle points | Hooks are non-fatal by default |

## Bean Override Rule

- Starter defaults are created with `@ConditionalOnMissingBean`.
- Registering your bean with the same type replaces the default.

## Where defaults come from

- Base starter defaults:
  - `kotlin-smtp-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/config/KotlinSmtpAutoConfiguration.kt`
- Relay starter defaults:
  - `kotlin-smtp-relay-spring-boot-starter/src/main/kotlin/io/github/kotlinsmtp/relay/config/KotlinSmtpRelayAutoConfiguration.kt`
