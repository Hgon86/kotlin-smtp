# Changelog

All notable changes to this project are documented in this file.

The format follows Keep a Changelog principles and semantic versioning intent.

## [Unreleased]

### Added
- CI policy to enforce documentation updates (`CHANGELOG.md`, `docs/MIGRATION.md`) when API baseline files change.
- Release governance docs: `docs/MIGRATION.md`, `docs/RELEASE_PROCESS.md`.
- Distributed Redis-backed rate limiter options for multi-node operation:
  - `smtp.rateLimit.backend=redis` for connection/message limits
  - `smtp.auth.rateLimitBackend=redis` for AUTH lockout tracking
- Core rate limiter extension SPI:
  - `SmtpRateLimiter`
  - `SmtpAuthRateLimiter`
  - builder hooks: `useRateLimiter`, `useAuthRateLimiter`
- Complete server composition documentation:
  - `docs/COMPLETE_SERVER_BLUEPRINT.md`
  - additional anti-spam/AV integration recipes in `docs/RECIPES.md`

### Changed
- PR template now includes API-change documentation checklist.
- Spooler now supports in-process parallel workers via `smtp.spool.workerConcurrency`.
- README/docs now clarify product positioning (engine framework vs complete mail product).

### Security
- No additional security changes recorded.

## [0.1.3] - 2026-02-23

### Added
- Binary compatibility validation baseline and API checks for core relay boundaries.
- CI quality gate expansion: `apiCheck`, JaCoCo report/verification, and coverage artifact upload.
- Operations and testing docs: `docs/OPERATIONS.md`, `docs/QUICKSTART.md`, `docs/TESTING_STRATEGY.md`, `docs/COVERAGE_ROADMAP.md`.
- Relay security guardrail option: `smtp.relay.outboundTls.failOnTrustAll`.
- Auth credential policy option: `smtp.auth.allowPlaintextPasswords`.

### Changed
- SMTP server lifecycle now supports configurable graceful shutdown timeout via `smtp.lifecycle.gracefulShutdownTimeoutMs`.
- Spool trigger cooldown is configurable via `smtp.spool.triggerCooldownMillis`.
- Inbound TLS config validation tightened (`minTlsVersion`, handshake timeout, cipher suite input checks).

### Security
- AUTH-related logs now mask sensitive identity/IP fields.
- Relay `trustAll` can be blocked at startup with `failOnTrustAll=true`.
- Plaintext auth credentials can be disallowed with `allowPlaintextPasswords=false`.

### Notes
- See repository tags/releases for full release artifacts.
