# Performance Measurement Guide

This document explains Kotlin SMTP performance in user-facing terms and provides reproducible measurement workflows.

## At-a-Glance Baseline (Local)

Measurement date: `2026-02-19`

Environment:
- OS: `Microsoft Windows 11 Home (10.0.26200)`
- CPU: `Intel(R) Core(TM) Ultra 5 125H` (Logical Cores: `18`)
- RAM: `31.59 GB`
- JDK: `21.0.8+12-LTS-250`

Representative scenario:
- Conditions: `8` concurrent clients, `4 KB` body, `1600` total messages
- Result: `195.31 emails/s`
- Time conversion:
  - `1,000` messages in about `5.12s`
  - `10,000` messages in about `51.20s`
  - `100,000` messages in about `8.53m`
- Latency: `avg/p50/p95/max = 32.85/10.91/87.50/1904.21 ms`

Interpretation:
- Most requests complete quickly (`p50 10.91ms`).
- 95% complete within `87.50ms` (`p95`).
- Long-tail outliers can appear (`max`).

## Scope and Variability

These numbers represent a baseline profile of the core SMTP transaction path under this repository's benchmark setup.

- They are not a universal SLA for all deployments.
- Throughput and latency can change based on library configuration and implementation choices.
- Common factors include TLS/auth enabled state, protocol handler logic, I/O strategy, storage or relay integration, and network distance.
- User code inside handlers (validation, persistence, external API calls) can dominate end-to-end latency.

Recommended practice:
- Treat this document as a reproducible reference point.
- Re-run the benchmark/profile commands with your own production-like settings and publish those results for final sizing.

## How to Judge “Good or Bad”

There is no absolute score; compare against your service target.

- If your peak target is `50` msgs/s: this baseline has significant headroom on one instance.
- If your peak target is `150` msgs/s: likely feasible, but headroom should be validated.
- If your peak target is `300` msgs/s: horizontal scaling is recommended.

Conservative planning rule:

- Recommended stable throughput = measured throughput x `0.6`
- Required instances = `ceil(target peak TPS / recommended stable throughput)`

Example with current baseline:
- Measured throughput: `195.31 emails/s`
- Recommended stable throughput: about `117 emails/s`

## Measurement Methods (Server Auto-Start)

Both methods start and stop the SMTP server automatically in the benchmark process.

1) JMH (reproducible JVM benchmark)

```bash
./gradlew :kotlin-smtp-benchmarks:jmh
```

2) End-to-end profile test (practical user-facing metrics)

```bash
./gradlew :kotlin-smtp-benchmarks:performanceTest
```

Workload override example:

```bash
./gradlew :kotlin-smtp-benchmarks:performanceTest \
  -Dkotlinsmtp.performance.clients=16 \
  -Dkotlinsmtp.performance.messagesPerClient=300 \
  -Dkotlinsmtp.performance.bodyBytes=16384
```

## JMH Baseline by Message Size (8 Threads)

| bodyBytes | throughput (ops/s) | latency p50 (ms) | latency p95 (ms) |
| --- | ---: | ---: | ---: |
| 256 | 117.714 | 13.304 | 104.569 |
| 4096 | 168.831 | 12.108 | 67.895 |
| 65536 | 58.951 | 43.123 | 274.281 |

Notes:
- In this benchmark, `ops/s` can be read as SMTP transactions per second.
- Results vary by SMTP pipeline behavior, OS scheduling, and GC.

## Publishing Checklist

When publishing performance numbers, include all of the following:

- Kotlin SMTP version (or commit SHA)
- JDK version
- CPU / RAM / OS
- Command used
- Workload (concurrency, message size, total count)

## Sharing Template

```text
Environment
- Version: <kotlin-smtp version or SHA>
- JDK: <vendor/version>
- CPU: <model>
- RAM: <size>
- OS: <name/version>

Workload
- clients: <N>
- messagesPerClient: <N>
- bodyBytes: <N>

Results
- Throughput: <value> emails/s
- 1,000 messages: <value> sec
- 10,000 messages: <value> sec
- Latency (avg/p50/p95/max): <a>/<b>/<c>/<d> ms
```

## Short Community Reply Template

```text
Baseline on my local machine (Win11, JDK 21, Core Ultra 5, 32GB RAM):
- ~195 emails/sec on 4KB messages (8 concurrent clients)
- ~5.1 sec for 1,000 messages
- p95 latency ~87.5 ms

Measured with reproducible commands:
- ./gradlew :kotlin-smtp-benchmarks:jmh
- ./gradlew :kotlin-smtp-benchmarks:performanceTest

I also publish environment + workload so others can reproduce the numbers.
```
