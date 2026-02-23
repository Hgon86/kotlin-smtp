# Configuration Guide

## Contents

1. [Basic Configuration](#basic-configuration)
2. [Listener Configuration](#listener-configuration)
3. [TLS Configuration](#tls-configuration)
4. [Spool Configuration](#spool-configuration)
5. [Authentication Configuration](#authentication-configuration)
6. [Relay Configuration](#relay-configuration)
7. [Rate Limit Configuration](#rate-limit-configuration)
8. [Storage Configuration](#storage-configuration)
9. [PROXY Protocol Configuration](#proxy-protocol-configuration)
10. [Feature Flags](#feature-flags)
11. [Lifecycle Configuration](#lifecycle-configuration)
12. [Validation](#validation)

## Basic Configuration

### Minimal Configuration

```yaml
smtp:
  port: 2525
  hostname: localhost
  routing:
    localDomain: mydomain.com
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
    listsDir: ./data/lists
  spool:
    dir: ./data/spool
  sentArchive:
    mode: TRUSTED_SUBMISSION
```

The default implementation stores sent message copies under
`smtp.storage.mailboxDir/<owner>/sent/`.
You can replace `SentMessageStore` with your own bean for S3 or DB-based storage.

Owner selection logic:
- Authenticated session: AUTH username (`authenticatedUsername`)
- Unauthenticated session: envelope sender local-part

`smtp.sentArchive.mode`:
- `TRUSTED_SUBMISSION` (default): store messages from AUTH sessions or external relay submissions
- `AUTHENTICATED_ONLY`: store only messages from AUTH sessions
- `DISABLED`: disable sent-mail archiving

### Full Example

See `docs/application.example.yml`.

## Listener Configuration

### Single Port Mode (default)

```yaml
smtp:
  port: 2525
  hostname: localhost
```

### Multi-Port Mode (listeners)

```yaml
smtp:
  listeners:
    # MTA inbound (25/2525)
    - port: 2525
      serviceName: ESMTP
      implicitTls: false
      enableStartTls: true
      enableAuth: true
      requireAuthForMail: false
      idleTimeoutSeconds: 300

    # Submission (587)
    - port: 587
      serviceName: SUBMISSION
      enableStartTls: true
      enableAuth: true
      requireAuthForMail: true
      idleTimeoutSeconds: 300

    # SMTPS (465)
    - port: 465
      serviceName: SMTPS
      implicitTls: true
      enableAuth: true
      requireAuthForMail: true
      idleTimeoutSeconds: 300
```

### Listener Options

| Option | Default | Description |
|------|--------|------|
| `port` | - | Listener port (required) |
| `serviceName` | ESMTP | SMTP banner/service name |
| `implicitTls` | false | Start TLS immediately on connect (SMTPS) |
| `enableStartTls` | true | Enable STARTTLS command |
| `enableAuth` | true | Enable AUTH command |
| `requireAuthForMail` | false | Require AUTH before MAIL FROM |
| `idleTimeoutSeconds` | 300 | Idle timeout in seconds (0 = disabled) |
| `proxyProtocol` | false | Accept PROXY protocol v1 on this listener |

## TLS Configuration

### STARTTLS (recommended)

```yaml
smtp:
  ssl:
    enabled: true
    certChainFile: /path/to/cert.pem
    privateKeyFile: /path/to/key.pem
    minTlsVersion: TLSv1.2
    handshakeTimeoutMs: 30000
```

### Implicit TLS (SMTPS/465)

```yaml
smtp:
  listeners:
    - port: 465
      implicitTls: true
  ssl:
    enabled: true
    certChainFile: /path/to/cert.pem
    privateKeyFile: /path/to/key.pem
```

### TLS Options

| Option | Default | Description |
|------|--------|------|
| `enabled` | false | Enable TLS |
| `certChainFile` | - | Certificate chain file path |
| `privateKeyFile` | - | Private key file path |
| `minTlsVersion` | TLSv1.2 | Minimum TLS version |
| `handshakeTimeoutMs` | 30000 | TLS handshake timeout (ms) |
| `cipherSuites` | [] | Explicit cipher suite list |

Validation notes:
- `minTlsVersion` must be `TLSv1.2` or `TLSv1.3`.
- `handshakeTimeoutMs` must be > 0.
- `cipherSuites` must not contain blank values.

## Spool Configuration

### Basic Configuration

```yaml
smtp:
  spool:
    type: auto
    dir: ./data/spool
    maxRetries: 5
    retryDelaySeconds: 60
    workerConcurrency: 1
```

### Redis Backend

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

- `type=auto` selects Redis if `StringRedisTemplate` exists, otherwise file spool.
- With `type=redis`, raw data, queue state, locks, and metadata are stored in Redis.
- Temporary files are created only during delivery and cleaned up immediately.
- If `StringRedisTemplate` is missing and Redis is selected, startup fails.
- Single/cluster/Sentinel Redis topology follows your application Redis configuration.

### Spool Options

| Option | Default | Description |
|------|--------|------|
| `type` | auto | Spool backend (`auto`, `file`, `redis`) |
| `dir` | - | Spool directory path (required) |
| `maxRetries` | 5 | Maximum retry attempts |
| `retryDelaySeconds` | 60 | Initial retry delay in seconds |
| `workerConcurrency` | 1 | Concurrent spool workers per in-process run |
| `triggerCooldownMillis` | 1000 | Cooldown for external spool triggers (ms) |
| `redis.keyPrefix` | `kotlin-smtp:spool` | Redis key prefix |
| `redis.maxRawBytes` | `26214400` | Max raw RFC822 bytes stored in Redis |
| `redis.lockTtlSeconds` | `900` | Redis lock TTL in seconds |

### Retry Policy

- Exponential backoff: 60s -> 120s -> 240s -> 480s -> capped at 600s
- Jitter: +-20%
- Maximum delay: 10 minutes (600s)

## Authentication Configuration

### Basic Auth

```yaml
smtp:
  auth:
    enabled: true
    required: false
    allowPlaintextPasswords: true
    users:
      user1: password1
      user2: "$2a$10$..."  # BCrypt hash supported
```

### Auth Rate Limiting

```yaml
smtp:
  auth:
    rateLimitEnabled: true
    rateLimitBackend: local # local | redis
    rateLimitMaxFailures: 5
    rateLimitWindowSeconds: 300
    rateLimitLockoutSeconds: 600
    rateLimitRedis:
      keyPrefix: kotlin-smtp:auth-ratelimit
```

### Authentication Options

| Option | Default | Description |
|------|--------|------|
| `enabled` | false | Enable SMTP AUTH |
| `required` | false | Require AUTH for all mail operations |
| `allowPlaintextPasswords` | true | Allow plaintext values in `smtp.auth.users` (set false to require BCrypt only) |
| `rateLimitEnabled` | true | Enable auth brute-force protection |
| `rateLimitBackend` | local | AUTH limiter backend (`local`, `redis`) |
| `rateLimitMaxFailures` | 5 | Max failures within window |
| `rateLimitWindowSeconds` | 300 | Failure tracking window (seconds) |
| `rateLimitLockoutSeconds` | 600 | Lockout duration (seconds) |
| `rateLimitRedis.keyPrefix` | `kotlin-smtp:auth-ratelimit` | Redis key prefix for AUTH limiter state |

## Relay Configuration

### Basic Relay Configuration

```yaml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: true
    allowedClientCidrs:
      - 10.0.0.0/8
      - 192.168.0.0/16
```

### Smart Host Configuration

```yaml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: true
    defaultRoute:
      host: smtp.provider.com
      port: 587
      startTlsEnabled: true
      username: relay-user
      password: ${RELAY_PASSWORD}
```

### Domain-Based Routing

```yaml
smtp:
  relay:
    enabled: true
    routes:
      - domain: example.com
        host: mx1.example.com
        port: 25
        startTlsEnabled: true
      - domain: "*"  # default catch-all route
        host: smtp.backup.com
        port: 587
```

### Outbound TLS

```yaml
smtp:
  relay:
    outboundTls:
      ports: [25, 587]
      startTlsEnabled: true
      startTlsRequired: false
      checkServerIdentity: true
      trustAll: false  # true only for local/dev testing
      failOnTrustAll: false # true blocks startup when trustAll is enabled
      trustHosts: []
      connectTimeoutMs: 15000
      readTimeoutMs: 15000
```

Security note:
- `failOnTrustAll=true` is recommended for production to prevent accidental insecure TLS trust configuration.

### Outbound Policy (MTA-STS / DANE)

```yaml
smtp:
  relay:
    outboundPolicy:
      mtaSts:
        enabled: false
        connectTimeoutMs: 3000
        readTimeoutMs: 5000
      dane:
        enabled: false
```

Notes:
- `mtaSts.enabled=true`: resolve `_mta-sts.<domain>` and fetch `https://mta-sts.<domain>/.well-known/mta-sts.txt`.
- `mode=enforce` is applied as strict policy (TLS required + certificate validation).
- `dane.enabled=true`: basic TLSA signal check via `_25._tcp.<domain>`.
- If both policies exist, stricter requirements are merged.

### Relay Options

| Option | Default | Description |
|------|--------|------|
| `enabled` | false | Enable outbound relay |
| `requireAuthForRelay` | true | Require AUTH before external relay |
| `allowedSenderDomains` | [] | Sender-domain allowlist for unauthenticated relay |
| `allowedClientCidrs` | [] | Client CIDR allowlist for unauthenticated relay |
| `outboundPolicy.mtaSts.enabled` | false | Enable MTA-STS policy resolution |
| `outboundPolicy.mtaSts.connectTimeoutMs` | 3000 | MTA-STS HTTPS connect timeout (ms) |
| `outboundPolicy.mtaSts.readTimeoutMs` | 5000 | MTA-STS HTTPS read timeout (ms) |
| `outboundPolicy.dane.enabled` | false | Enable basic DANE TLSA policy signal |

`allowedSenderDomains` and `allowedClientCidrs` can be used together.
An unauthenticated relay request must satisfy both conditions when both are configured.
For more advanced rules (DB lookups, IP reputation, policy engine), provide a custom `RelayAccessPolicy` bean.

## Rate Limit Configuration

### Connection and Message Limits

```yaml
smtp:
  rateLimit:
    backend: local # local | redis
    maxConnectionsPerIp: 10
    maxMessagesPerIpPerHour: 100
    redis:
      keyPrefix: kotlin-smtp:conn-ratelimit
      connectionCounterTtlSeconds: 900
```

### Rate Limit Options

| Option | Default | Description |
|------|--------|------|
| `maxConnectionsPerIp` | 10 | Maximum concurrent connections per IP |
| `maxMessagesPerIpPerHour` | 100 | Maximum accepted messages per IP per hour |
| `backend` | local | Connection/message limiter backend (`local`, `redis`) |
| `redis.keyPrefix` | `kotlin-smtp:conn-ratelimit` | Redis key prefix for distributed limiter state |
| `redis.connectionCounterTtlSeconds` | 900 | Redis TTL for distributed connection counters |

## Storage Configuration

### File-Based Storage (default)

```yaml
smtp:
  storage:
    mailboxDir: ./data/mailboxes
    tempDir: ./data/temp
    listsDir: ./data/lists
```

### With Environment Variables

```yaml
smtp:
  storage:
    mailboxDir: ${SMTP_MAILBOX_DIR:./data/mailboxes}
    tempDir: ${SMTP_TEMP_DIR:./data/temp}
    listsDir: ${SMTP_LISTS_DIR:./data/lists}
```

## PROXY Protocol Configuration

```yaml
smtp:
  listeners:
    - port: 2525
      proxyProtocol: true

  proxy:
    trustedCidrs:
      - 127.0.0.1/32
      - ::1/128
      - 10.0.0.0/8
      - 172.16.0.0/12
```

## Feature Flags

```yaml
smtp:
  features:
    vrfyEnabled: false  # VRFY command (off by default)
    etrnEnabled: false  # ETRN command (admin use-case)
    expnEnabled: false  # EXPN command (off by default)
```

## Lifecycle Configuration

```yaml
smtp:
  lifecycle:
    gracefulShutdownTimeoutMs: 30000
```

| Option | Default | Description |
|------|--------|------|
| `gracefulShutdownTimeoutMs` | 30000 | Graceful stop timeout for SMTP server shutdown (ms) |

## Validation

Configuration validation runs at startup:

- Required path checks
- Port range validation (`0..65535`)
- TLS consistency checks
- Rate limit value validation

If validation fails, the application does not start.
