# Complete Server Blueprint

This document explains how to turn Kotlin SMTP from an SMTP engine into a complete mail server stack.

## 1) Engine vs Complete Product

Kotlin SMTP is intentionally positioned as a reusable SMTP engine.

- Engine scope (this repository):
  - SMTP receive pipeline and protocol correctness
  - AUTH/TLS/rate-limit guardrails
  - Spool/retry/relay boundaries
  - Extension SPI for storage/policy/events/transport
- Complete server scope (assembled by integrator):
  - Threat filtering (anti-spam, malware scan, content policy)
  - User mailbox protocols (IMAP/POP3/JMAP)
  - Admin control plane (queue inspection, replay, suppression, audit)

## 2) Recommended Module Roadmap

When building a full server platform, add modules around the core in this order.

### Phase A: Security/Policy Layer

- Goal:
  - Block abusive traffic and enforce organization policy before final delivery.
- Typical components:
  - SPF/DKIM/DMARC evaluation service
  - Content policy engine (headers/body/attachments)
  - Reputation and deny/allow controls
- Kotlin SMTP touch points:
  - `RelayAccessPolicy`
  - `SmtpEventHook`
  - custom `SmtpProtocolHandler` and delivery policy edges

### Phase B: Control Plane Layer

- Goal:
  - Operate mail flow safely in production.
- Typical components:
  - Queue browse/retry/purge API
  - Delivery audit API
  - Operator dashboard and incident workflows
- Kotlin SMTP touch points:
  - spool metadata and lock abstractions
  - event hooks and metrics

### Phase C: End-User Access Layer

- Goal:
  - Provide mailbox access to end users.
- Typical components:
  - IMAP/POP3/JMAP service
  - Mailbox index/search service
  - message retention and archive policies
- Kotlin SMTP touch points:
  - custom mailbox/message storage strategy
  - delivery and archival integration boundaries

## 3) External Integration Recipes (Anti-spam/AV/Policy)

Use these patterns to integrate common production defenses.

### Pattern 1: Policy decision before relay

- Use `RelayAccessPolicy` for sender/domain/IP/business rule gates.
- For heavy checks (reputation, external policy API), cache decisions and fail closed for high-risk paths.

### Pattern 2: Asynchronous scanning pipeline

- Accept and spool first, then run scanner workers (AV/content classifier).
- If scan result is malicious, quarantine and emit DSN/incident event according to your policy.
- Keep synchronous SMTP command path lightweight to avoid latency spikes.

### Pattern 3: Event-first observability and audit

- Publish `SmtpEventHook` events to your event bus (Kafka/PubSub).
- Build audit trails and incident analytics outside command handlers.
- Keep hooks non-blocking and idempotent.

See also: `RECIPES.md`, `SECURITY_RELAY.md`, `OPERATIONS.md`.

## 4) James Replacement Path (Staged Adoption)

Teams replacing a monolithic server can use this migration path.

### Stage 1: SMTP Inbound First

- Run Kotlin SMTP for inbound acceptance and local relay policy.
- Keep existing mailbox access stack unchanged.

### Stage 2: Relay + Retry Ownership

- Move outbound relay/spool ownership to Kotlin SMTP.
- Add production guardrails: TLS policy, distributed rate limits, operations runbook.

### Stage 3: Policy Ecosystem Integration

- Connect anti-spam/AV/policy engines through SPI boundaries.
- Add quarantine and compliance workflows.

### Stage 4: Full Platform Composition

- Add mailbox access protocols and control plane.
- Decommission legacy server modules incrementally after parity checks.

## 5) Production Readiness Checklist for Complete Server Builds

- Protocol and regression tests for your custom policies
- Queue and DSN operational runbooks
- SLO/alert thresholds for spool backlog, retry delay, and failure classes
- Security baseline enforcement (TLS/Auth/relay policy)
- Versioned integration contracts and migration notes
