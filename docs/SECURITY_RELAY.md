# Relay Security Guide

Relay misconfiguration can create an open relay. This guide provides safe defaults and checks.

## 1) Safe baseline

Recommended defaults:

```yaml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: true
    allowedSenderDomains: []
    allowedClientCidrs: []
```

Why:
- `requireAuthForRelay=true` is the primary safety control.
- Empty allowlists avoid accidental unauthenticated exceptions.

## 2) Controlled unauthenticated relay (internal-only)

If you must allow unauthenticated relay for specific internal systems, restrict by CIDR and domain:

```yaml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: false
    allowedClientCidrs:
      - 10.0.0.0/8
      - 192.168.0.0/16
    allowedSenderDomains:
      - internal.example.com
```

Guideline:
- Use both client CIDR and sender-domain constraints.
- Do not expose this mode to public internet.

## 3) Custom policy for production

Implement `RelayAccessPolicy` when static allowlists are insufficient.

Use cases:
- IP reputation checks
- tenant-specific sender rules
- time-based policy
- DB-driven allow/deny lists

## 4) Outbound TLS hardening

Recommended:

```yaml
smtp:
  relay:
    outboundTls:
      startTlsEnabled: true
      startTlsRequired: false
      checkServerIdentity: true
      trustAll: false
```

Notes:
- `trustAll=true` is only for local/dev testing.
- Keep `checkServerIdentity=true` in production.

## 5) Operational checks

Run these checks before production:

1. Authenticated relay succeeds
2. Unauthenticated relay is denied (unless explicitly allowed)
3. Internal CIDR exceptions behave as expected
4. Sender-domain exceptions behave as expected
5. Relay audit logs include enough decision context

## 6) Common anti-patterns

- `requireAuthForRelay=false` with no allowlists
- wildcard sender exceptions for internet-facing systems
- `trustAll=true` in production
- no monitoring for relay denial/acceptance rates
