# Migration Guide

This guide tracks user-facing migration steps between Kotlin SMTP versions.

## Unreleased

### If you use relay TLS options
- New option: `smtp.relay.outboundTls.failOnTrustAll`
  - Recommended for production: set to `true`.
  - Behavior: startup fails when any relay path enables `trustAll=true`.

### If you use in-memory AUTH users
- New option: `smtp.auth.allowPlaintextPasswords`
  - Default: `true` (backward compatible).
  - Recommended for production: set to `false` and migrate all `smtp.auth.users` values to BCrypt hashes.

### If you manage shutdown behavior
- New option: `smtp.lifecycle.gracefulShutdownTimeoutMs`.
  - Use to align SMTP stop timeout with deployment termination windows.

### If you trigger spool manually (ETRN/admin)
- New option: `smtp.spool.triggerCooldownMillis`.
  - Controls minimum interval between accepted external trigger requests.

### If you operate multi-instance SMTP nodes
- New options:
  - `smtp.rateLimit.backend` (`local` | `redis`)
  - `smtp.auth.rateLimitBackend` (`local` | `redis`)
  - `smtp.rateLimit.redis.*`, `smtp.auth.rateLimitRedis.*`
- When using `redis`, your application must provide `StringRedisTemplate`.

### If you want higher spool throughput per node
- New option: `smtp.spool.workerConcurrency` (default `1`).
  - Increase gradually while monitoring relay/memory pressure.

## 0.1.3

No breaking migration steps documented.

## Migration Checklist Template

When upgrading versions, verify:

1. `./gradlew test apiCheck jacocoTestCoverageVerification` passes.
2. Security defaults are reviewed (`trustAll`, auth credential policy).
3. Any new config keys are explicitly set in production manifests.
4. Operations runbook updates are reviewed (`docs/OPERATIONS.md`).
