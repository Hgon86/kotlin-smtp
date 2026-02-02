## Core Extraction Plan (Spring-Free Core)

This plan intentionally avoids large rewrites. The goal is to split modules while keeping runtime behavior and tests intact.

### Step 0: Freeze Behavior

- Keep current integration tests green.
- Add only a few extra tests if needed (AUTH + STARTTLS happy paths) as a safety net.

### Step 1: Define Module Targets

Target structure (minimal):

- `kotlin-smtp-core`
  - SMTP engine + protocol + extension interfaces
  - no Spring annotations/dependencies

- `kotlin-smtp-app` (temporary name)
  - Spring Boot entrypoint
  - reads configuration, wires beans, starts servers

### Step 2: Move Code By Responsibility (No Behavior Change)

Move into `core` first:
- `server/*` (SmtpServer, SmtpSession, decoder, rate limiter, proxy support)
- `protocol/*` (commands, parsers, handler interfaces)
- `model/*`, `utils/*`, `exception/*`
- `auth/*` (AuthService, rate limiter) but remove Spring usage (see Step 3)
- `storage/*` (MessageStore interface)

Keep in `app` initially:
- `config/SmtpServerConfig.kt`
- `server/SmtpServerRunner.kt`
- `KotlinSmtpApplication.kt`
- default implementations that are likely to expand soon (spool/relay/local mailbox)

### Step 3: Remove Spring Couplings From Core

Core should not reference:
- `@Configuration`, `@Component`, `@ConfigurationProperties`
- Spring events (`ApplicationReadyEvent`, `ContextClosedEvent`)

If core needs lifecycle hooks, expose them as plain Kotlin APIs (start/stop) and let the host call them.

### Step 4: Host Wires Core

In `app`:
- keep `SmtpServerConfig` as the single wiring point
- create `SmtpServer` instances and call `start()` via `SmtpServerRunner`

This answers the common concern:
"If core removes Spring, does the server stop working?"

No. The app module (Spring) still starts the server. The core module is only the engine.

### Step 5: Decide What "Core" Means (One Required Decision)

There are two realistic scopes:

Option A (Recommended): engine-only core
- core contains only the inbound SMTP engine and interfaces
- relay/spool/file mailbox are shipped as separate modules (or as an example app)

Option B: batteries-included core
- core contains relay/spool/local mailbox defaults
- faster to ship, but the core API drags heavy deps (dnsjava, jakarta mail, json)

### Step 6: After Core Extraction

- Introduce `kotlin-smtp-starter` (Spring Boot auto-config) when the boundaries are stable.
- Add R2DBC/PostgreSQL implementations as separate modules (auth + metadata) to keep core clean.
