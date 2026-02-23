# Operations Runbook

This guide focuses on operating Kotlin SMTP in production-like environments.

## Scope

- This runbook is optional for pure embedded-library use.
- If you use Kotlin SMTP as a production SMTP service (single node or clustered), these operations patterns are recommended.
- Kubernetes/Prometheus examples are reference templates, not required dependencies.

## 1. Deployment Topologies

### Single node (small volume)
- Use file spool (`smtp.spool.type=file`) and local disk paths.
- Keep SMTP and app logs on the same host for simple debugging.

### Multi node (recommended)
- Use Redis spool (`smtp.spool.type=redis`) so all nodes share queue state.
- Keep `smtp.spool.redis.keyPrefix` isolated per environment (for example, `kotlin-smtp:prod:spool`).
- Run at least two SMTP instances behind L4/L7 load balancer.

## 2. Startup Checklist

- Verify required paths are writable:
  - `smtp.storage.mailboxDir`
  - `smtp.storage.tempDir`
  - `smtp.storage.listsDir`
  - `smtp.spool.dir`
- Verify relay security defaults:
  - `smtp.relay.requireAuthForRelay=true` (internet-facing default)
  - `smtp.relay.outboundTls.trustAll=false`
  - `smtp.relay.outboundTls.failOnTrustAll=true` for production safety
- Verify auth credential policy:
  - Use BCrypt values in `smtp.auth.users`
  - Prefer `smtp.auth.allowPlaintextPasswords=false` in production
- Verify conservative protocol flags:
  - `smtp.features.vrfyEnabled=false`
  - `smtp.features.expnEnabled=false`
  - `smtp.features.etrnEnabled=false` (enable only for admin use cases)

## 3. Health and Probes

If you expose HTTP management endpoints, configure probes with Spring Boot Actuator.

Reference (Spring Boot 3.4.1 via Context7):
- `management.endpoint.health.probes.enabled=true`
- `management.endpoint.health.probes.add-additional-paths=true`

Example:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
        add-additional-paths: true
```

This exposes `/actuator/health/liveness` and `/actuator/health/readiness` (and `/livez`, `/readyz` on main port when additional paths are enabled).

## 4. Graceful Shutdown

Kotlin SMTP server shutdown path is graceful by default in core (`SmtpServer.stop()` default timeout is 30s).

You can configure SMTP shutdown timeout explicitly:

```yaml
smtp:
  lifecycle:
    gracefulShutdownTimeoutMs: 30000
```

If your host application also has HTTP server traffic, use Spring Boot graceful shutdown settings:

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
```

Reference (Spring Boot 3.4.1 via Context7): graceful shutdown and `timeout-per-shutdown-phase`.

## 5. SLO and Alert Baseline

Use these as initial baseline targets, then tune with real workload.

### Suggested SLOs
- SMTP availability: >= 99.9%
- Queue drain latency (P95): <= 300s
- Retry saturation: dropped messages ratio <= 0.1%

### Alert candidates (Micrometer)
- Queue backlog growth:
  - `smtp.spool.pending` continuously increasing for N minutes
- Drop spike:
  - `rate(smtp_spool_dropped_total[5m]) > 0`
- Retry pressure:
  - high `smtp_spool_retry_scheduled_total` and increasing `smtp.spool.retry.delay.seconds`
- Queue age regression:
  - `smtp.spool.queue.age.seconds` percentile drift (especially `outcome=dropped`)
- Recipient failure trend:
  - `smtp.spool.delivery.failure.total{kind="permanent"}` increase by `reason`

### Baseline thresholds by environment

Use these as starting points and tune with real traffic.

| Metric | Dev/Staging | Production |
|------|------|------|
| `smtp_spool_pending` | warn > 200 for 10m | warn > 1000 for 10m, critical > 3000 for 10m |
| `increase(smtp_spool_dropped_total[5m])` | > 0 (warn) | > 0 (critical) |
| `histogram_quantile(0.95, rate(smtp_spool_retry_delay_seconds_bucket[10m]))` | > 180s (warn) | > 300s (warn), > 480s (critical) |
| `increase(smtp_spool_delivery_failure_total{kind="permanent"}[10m])` | > 20 (warn) | > 50 (warn), > 150 (critical) |

## 6. Incident Playbooks

### A) Queue backlog keeps growing
1. Check `smtp.spool.pending` trend and current relay failure logs.
2. Check Redis connectivity/timeouts (for redis spool) or disk I/O (for file spool).
3. Verify DNS/MX reachability to major target domains.
4. Temporarily reduce inbound load (LB rate limiting) if backlog threatens disk/memory.
5. If trigger cooldown is too aggressive for maintenance, temporarily lower `smtp.spool.triggerCooldownMillis` under controlled window.

### B) Permanent failure surge
1. Inspect `smtp.spool.delivery.failure.total` by `reason` and `kind`.
2. If `policy` spikes, verify relay auth/CIDR/domain policy changes.
3. If `dns` spikes, verify resolver health and upstream DNS incidents.
4. Review DSN generation volume and avoid feedback loops.
5. If `tls` spikes, compare recent cert/CA changes and `checkServerIdentity` policy.

### C) Trigger abuse (ETRN/admin)
1. Keep `smtp.features.etrnEnabled=false` unless required.
2. If enabled, require authenticated sessions and monitor trigger frequency.
3. Current spooler includes trigger cooldown; repeated calls inside cooldown return unavailable.
4. Tune cooldown explicitly when needed:

```yaml
smtp:
  spool:
    triggerCooldownMillis: 1000
```

### D) Shutdown/rolling update hangs
1. Verify `smtp.lifecycle.gracefulShutdownTimeoutMs` is set to realistic value for current message size profile.
2. If application also serves HTTP, align Spring Boot graceful shutdown timeout with SMTP timeout.
3. Confirm readiness probe flips before pod termination in orchestrator.
4. If frequent timeout overruns occur, inspect relay target latency and spool retry pressure.

## 7. Deployment Templates

### Docker Compose skeleton

Use as a starting point for local production-like rehearsal.

Ready-to-copy file: `docs/templates/docker-compose.smtp.yml`

```yaml
services:
  redis:
    image: redis:7
    ports:
      - "6379:6379"

  smtp-app:
    image: your-org/kotlin-smtp-app:latest
    environment:
      SMTP_SPOOL_DIR: /data/spool
      SMTP_MAILBOX_DIR: /data/mailboxes
      SMTP_TEMP_DIR: /data/temp
      SMTP_LISTS_DIR: /data/lists
    ports:
      - "2525:2525"
    volumes:
      - ./data:/data
    depends_on:
      - redis
```

### Kubernetes probe skeleton

Ready-to-copy file: `docs/templates/k8s-probes.yaml`

Deployment starter template: `docs/templates/k8s-deployment.smtp.yaml`

Service template: `docs/templates/k8s-service.smtp.yaml`

Pod disruption budget template: `docs/templates/k8s-pdb.smtp.yaml`

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  periodSeconds: 10
  failureThreshold: 3
```

### Prometheus alert rule skeleton

Ready-to-copy file: `docs/templates/prometheus-smtp-alerts.yml`

## 8. Rolling Update Guide (Kubernetes)

1. Ensure at least 2 replicas and active readiness probe.
2. Apply/update `Deployment`, `Service`, and `PodDisruptionBudget` templates.
3. Start rollout and monitor readiness transition.
4. Verify no sustained increase in `smtp_spool_pending` and `smtp_spool_dropped_total` during rollout.
5. If rollback is needed, revert deployment image/tag and keep spool backend unchanged.

## 9. Release/Change Checklist

- Run: `./gradlew test apiCheck`
- If performance-sensitive path changed, also run benchmark profile:
  - `./gradlew :kotlin-smtp-benchmarks:jmh`
  - `./gradlew :kotlin-smtp-benchmarks:performanceTest`
- Confirm docs are aligned with behavior/config changes (`README.md`, `CONFIGURATION.md`, `OPERATIONS.md`).

## 10. Quick Operations Checklist

Before production deploy:
- [ ] `./gradlew test apiCheck` passed
- [ ] `smtp.relay.requireAuthForRelay=true` confirmed for internet-facing listeners
- [ ] `smtp.relay.outboundTls.trustAll=false` confirmed
- [ ] `smtp.features.etrnEnabled` reviewed (off unless required)
- [ ] spool backend selected (`file` or `redis`) and capacity checked
- [ ] probe endpoints reachable (`/actuator/health/liveness`, `/actuator/health/readiness`)
- [ ] alert rules loaded and notification channel verified
