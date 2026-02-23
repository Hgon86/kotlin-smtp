# Kotlin SMTP

A Netty-based Kotlin SMTP server library. The `core` module provides a Spring-free SMTP engine, while the `starter` modules offer Spring Boot auto-configuration for immediate use.

## Product Positioning

Kotlin SMTP is a **server engine framework**, not a monolithic complete mail product.

- What it provides out of the box:
  - SMTP receive path (protocol/session/TLS/auth)
  - Spool/retry/relay boundary
  - Extension SPI for policy, storage, relay, and hooks
- What you assemble on top for a complete server:
  - Anti-spam/anti-malware/policy engines
  - User mailbox access protocols (IMAP/POP3/JMAP)
  - Admin control plane (queue management/API/UI/ops automation)

See the `docs` directory for more information.

## Module Structure

```text
kotlin-smtp/
├── kotlin-smtp-core                         # Spring-free SMTP engine
├── kotlin-smtp-spring-boot-starter          # Inbound-focused starter (auto-config + default impl)
├── kotlin-smtp-relay                        # Outbound relay API boundary
├── kotlin-smtp-relay-jakarta-mail           # Relay implementation (dnsjava + jakarta-mail)
├── kotlin-smtp-relay-spring-boot-starter    # Relay auto-configuration
├── kotlin-smtp-benchmarks                   # JMH + end-to-end performance profile
└── kotlin-smtp-example-app                  # Example consumer application
```

This modular structure is intentional:
- `core`: Protocol/session/TLS/Auth/framing correctness
- `starter`: Quick startup experience + file-based default implementation
- `relay*`: Outbound delivery boundary separated as optional modules

## Key Features

- RFC 5321 core commands: `EHLO/HELO`, `MAIL`, `RCPT`, `DATA`, `RSET`, `QUIT`
- `BDAT` (Chunking), `STARTTLS`, `AUTH PLAIN/LOGIN`
- SMTPUTF8/IDN boundary handling
- PROXY protocol (v1), rate limiting
- ETRN/VRFY/EXPN (feature flags)
- Spool/retry/DSN (RFC 3464) handling
- Outbound relay hardening (Null MX detection, permanent/transient classification)
- Automatic `Date` / `Message-ID` supplementation when missing
- Optional outbound policy layer (MTA-STS / DANE basic resolver)

## Quick Start (Starter)

### 1) Dependencies

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("io.github.hgon86:kotlin-smtp-spring-boot-starter:VERSION")
}
```

### 2) Minimal Configuration

```yaml
smtp:
  port: 2525
  hostname: localhost
  routing:
    localDomain: local.test
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
    listsDir: ./data/lists
  spool:
    type: auto # auto | file | redis
    dir: ./data/spool
    maxRetries: 5
    retryDelaySeconds: 60
    workerConcurrency: 1
  sentArchive:
    mode: TRUSTED_SUBMISSION # TRUSTED_SUBMISSION | AUTHENTICATED_ONLY | DISABLED
```

To use Redis as the spool backend:

```yaml
smtp:
  spool:
    type: redis
    dir: ./data/spool
    redis:
      keyPrefix: kotlin-smtp:spool
      maxRawBytes: 26214400
      lockTtlSeconds: 900
```

- `type=auto` automatically selects Redis if a `StringRedisTemplate` bean exists, otherwise uses file storage.
- With `type=redis`, queue/lock/metadata are stored in Redis.
- Raw `.eml` content is also stored in Redis (no persistent file storage).
- Temporary files are created only at delivery time and cleaned up immediately after use.
- Your application must provide a `StringRedisTemplate` bean.
- Redis single/cluster/Sentinel configurations follow your application's settings.

The default implementation stores sent mail copies in `mailboxDir/<owner>/sent/`:
- Authenticated sessions use the AUTH user (`authenticatedUsername`) as the owner.
- Unauthenticated submissions use the envelope sender's local-part as the owner.
- You can replace this with S3/DB+ObjectStorage by registering a custom `SentMessageStore` bean.

Sent mailbox archiving is controlled via `smtp.sentArchive.mode`:
- `TRUSTED_SUBMISSION` (default): Store messages from AUTH sessions or external relay submissions
- `AUTHENTICATED_ONLY`: Store only AUTH session messages
- `DISABLED`: Do not store sent mail

To restrict unauthenticated relay submissions by IP, use `smtp.relay.allowedClientCidrs`.
For more complex rules (DB lookups, internal policy engines), implement a custom `RelayAccessPolicy` bean.

To use distributed rate limiting across multiple instances (optional):

```yaml
smtp:
  rateLimit:
    backend: redis
    redis:
      keyPrefix: kotlin-smtp:conn-ratelimit
      connectionCounterTtlSeconds: 900
  auth:
    rateLimitEnabled: true
    rateLimitBackend: redis
    rateLimitRedis:
      keyPrefix: kotlin-smtp:auth-ratelimit
```

- `rateLimit.backend=redis` enables distributed connection/message limits.
- `auth.rateLimitBackend=redis` enables distributed AUTH failure lockouts.
- Both modes require a `StringRedisTemplate` bean.

To enable outbound domain policy integration (optional):

```yaml
smtp:
  relay:
    outboundPolicy:
      mtaSts:
        enabled: true
        connectTimeoutMs: 3000
        readTimeoutMs: 5000
      dane:
        enabled: false
```

- MTA-STS `mode=enforce` upgrades relay policy to require TLS + certificate validation.
- DANE basic mode uses `_25._tcp.<domain>` TLSA presence as a strict TLS signal.

See `docs/application.example.yml` for a complete configuration example.

## Using Core Standalone

```kotlin
import io.github.kotlinsmtp.server.SmtpServer

val server = SmtpServer.create(2525, "smtp.example.com") {
    serviceName = "example-smtp"
    listener.enableStartTls = true
    listener.enableAuth = false
}

server.start()
```

## Observability

Micrometer integration **does not add endpoints to the SMTP port**:
- SMTP continues to operate on its existing port (e.g., 2525)
- Metrics are exposed only through the Spring Actuator management channel (opt-in)

Default metrics include:
- `smtp.connections.active`
- `smtp.sessions.started.total`, `smtp.sessions.ended.total`
- `smtp.messages.accepted.total`, `smtp.messages.rejected.total`
- `smtp.spool.pending`, `smtp.spool.queued.total`, `smtp.spool.completed.total`
- `smtp.spool.dropped.total`, `smtp.spool.retry.scheduled.total`
- `smtp.spool.delivery.recipients.total{result=delivered|transient_failure|permanent_failure}`

Prometheus exposure example (optional):

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}
```

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

In this case, `/actuator/prometheus` is available only on the **management port**.

## Running the Example App

```bash
./gradlew :kotlin-smtp-example-app:bootRun
```

## Performance Measurement

Run JMH benchmark (automatically starts local SMTP server inside benchmark process):

```bash
./gradlew :kotlin-smtp-benchmarks:jmh
```

Run end-to-end profile test (throughput + latency summary):

```bash
./gradlew :kotlin-smtp-benchmarks:performanceTest
```

Current local baseline example:
- `~195 emails/s` at `4KB`, `8` concurrent clients
- `~5.1s` per `1,000` messages
- `p95 latency ~87.5ms`

Override workload for profile test (optional):

```bash
./gradlew :kotlin-smtp-benchmarks:performanceTest \
  -Dkotlinsmtp.performance.clients=16 \
  -Dkotlinsmtp.performance.messagesPerClient=300 \
  -Dkotlinsmtp.performance.bodyBytes=16384
```

## Documentation

- `docs/QUICKSTART.md`: 10-minute guide to initial setup and verification
- `docs/ARCHITECTURE.md`: Runtime architecture and boundaries
- `docs/CONFIGURATION.md`: Configuration reference
- `docs/EXTENSION.md`: Extension strategy, override model, and goal-to-interface mapping
- `docs/RECIPES.md`: Minimal extension recipes
- `docs/LIFECYCLE.md`: Runtime lifecycle and hook timing
- `docs/SECURITY_RELAY.md`: Relay security hardening guide


## License

Apache License 2.0 (`LICENSE`)
