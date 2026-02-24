# Relay Security Guide

Relay misconfiguration can create an open relay. This guide provides safe defaults and checks.

## 1) Safe baseline

Recommended defaults:

```yaml
smtp:
  relay:
    enabled: true
    requireAuthForRelay: true
    failOnOpenRelay: true
    allowedSenderDomains: []
    allowedClientCidrs: []
```

Why:
- `requireAuthForRelay=true` is the primary safety control.
- Empty allowlists avoid accidental unauthenticated exceptions.
- `failOnOpenRelay=true` makes misconfiguration fail fast at startup.

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
      failOnTrustAll: true
```

Notes:
- `trustAll=true` is only for local/dev testing.
- `failOnTrustAll=true` prevents accidental startup with insecure trust-all TLS.
- Keep `checkServerIdentity=true` in production.

## 5) Operational checks

Run these checks before production:

1. Authenticated relay succeeds
2. Unauthenticated relay is denied (unless explicitly allowed)
3. Internal CIDR exceptions behave as expected
4. Sender-domain exceptions behave as expected
5. Relay audit logs include enough decision context

## 6) Domain policy hardening (MTA-STS / DANE)

For internet-facing relay, you can enable outbound domain policy integration:

```yaml
smtp:
  relay:
    outboundPolicy:
      mtaSts:
        enabled: true
      dane:
        enabled: false
```

Behavior:
- MTA-STS `mode=enforce` upgrades relay delivery to strict TLS + certificate validation.
- DANE basic mode uses `_25._tcp.<domain>` TLSA presence as strict TLS signal.
- When both are present, stricter requirements are merged.

Operational note:
- Start with MTA-STS only, observe failures, then enable DANE where DNSSEC operations are mature.

## 7) Common anti-patterns

- `requireAuthForRelay=false` with no allowlists
- wildcard sender exceptions for internet-facing systems
- `trustAll=true` in production
- no monitoring for relay denial/acceptance rates
- enabling strict domain policies without monitoring transient failure rates
