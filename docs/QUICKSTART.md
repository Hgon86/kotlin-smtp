# Quickstart (10 Minutes)

This quickstart helps you run Kotlin SMTP safely with minimal setup.

## 1) Add dependencies

```kotlin
dependencies {
    implementation("io.github.hgon86:kotlin-smtp-spring-boot-starter:0.1.3")
    implementation("io.github.hgon86:kotlin-smtp-relay-spring-boot-starter:0.1.3")
}
```

## 2) Minimal safe configuration

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
    type: file

  relay:
    enabled: true
    requireAuthForRelay: true
    outboundTls:
      trustAll: false
      failOnTrustAll: true

  auth:
    enabled: true
    required: false
    allowPlaintextPasswords: false
    users:
      user: "$2a$10$CwTycUXWue0Thq9StjUM0uJ8ZrYcGQpFvWY9Y1KfK1aG1H0a4V/2m"
```

## 3) Run and smoke test

Run app:

```bash
./gradlew bootRun
```

SMTP smoke test:

```text
EHLO client.local
MAIL FROM:<sender@mydomain.com>
RCPT TO:<user@mydomain.com>
DATA
Subject: Hello

Quickstart test message
.
QUIT
```

## Next

- Production operations: `docs/OPERATIONS.md`
- Full config reference: `docs/CONFIGURATION.md`
- Security hardening: `docs/SECURITY_RELAY.md`
