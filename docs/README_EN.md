# Kotlin SMTP Documentation

## Table of Contents

1. [Getting Started](README_EN.md) - Quick start guide
2. [Architecture](ARCHITECTURE_EN.md) - System architecture and how it works
3. [Configuration Guide](CONFIGURATION_EN.md) - Detailed configuration options
4. [Extending](EXTENSION_EN.md) - Custom implementations and extension methods

## Overview

Kotlin SMTP is a Netty-based Kotlin SMTP server library.

### Features

- **Spring-free Core**: SMTP engine written in pure Kotlin
- **Spring Boot Integration**: Easy configuration with starter modules
- **SMTPUTF8/IDN Support**: Handles internationalized email addresses
- **TLS Support**: STARTTLS and Implicit TLS
- **PROXY Protocol**: Original client IP detection behind load balancers
- **Modular**: Use only the features you need

### Module Structure

```
kotlin-smtp/
├── kotlin-smtp-core/                    # Spring-free SMTP engine
├── kotlin-smtp-spring-boot-starter/     # Spring Boot auto-configuration
├── kotlin-smtp-relay/                   # Relay API (optional)
├── kotlin-smtp-relay-jakarta-mail/      # Jakarta Mail relay implementation (optional)
└── kotlin-smtp-relay-spring-boot-starter/ # Relay Spring Boot integration (optional)
```

## Quick Start

### 1. Add Dependency

```kotlin
dependencies {
    implementation("io.github.kotlinsmtp:kotlin-smtp-spring-boot-starter:0.1.0")
}
```

### 2. Configure (application.yml)

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
    maxRetries: 5
    retryDelaySeconds: 60
```

### 3. Run

```bash
./gradlew bootRun
```

## License

Apache License 2.0
