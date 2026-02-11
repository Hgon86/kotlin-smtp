# Extension Guide

This guide focuses on the practical question:

"What do I implement and register to customize behavior?"

If you want a quick lookup table, start here:
- `docs/EXTENSION_MATRIX.md`

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
- `SmtpProtocolHandler`
- `SmtpEventHook`
- `SmtpUserHandler`
- `SmtpMailingListHandler`
- `InboundRoutingPolicy`
- Relay-related: `MailRelay`, `RelayAccessPolicy`, `DsnSender`, `RelayRouteResolver`

## 2) Minimum mental model

- **Inbound receive path**: SMTP command handling -> `SmtpProtocolHandler` -> `MessageStore`
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
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/protocol/handler/SmtpProtocolHandler.kt`
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/spi/SmtpEventHook.kt`
- `kotlin-smtp-core/src/main/kotlin/io/github/kotlinsmtp/routing/InboundRoutingPolicy.kt`
- `kotlin-smtp-relay/src/main/kotlin/io/github/kotlinsmtp/relay/api/MailRelay.kt`
- `kotlin-smtp-relay/src/main/kotlin/io/github/kotlinsmtp/relay/api/RelayAccessPolicy.kt`

## 7) Recommended reading order

If you are new:
1. `docs/ARCHITECTURE.md`
2. `docs/CONFIGURATION.md`
3. `docs/EXTENSION_MATRIX.md`
4. `docs/RECIPES.md`
5. `docs/SECURITY_RELAY.md`

If you are implementing production customization:
1. `docs/LIFECYCLE.md`
2. `docs/EXTENSION_MATRIX.md`
3. `docs/RECIPES.md`
4. `docs/SECURITY_RELAY.md`
