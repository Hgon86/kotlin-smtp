# Testing Strategy

This document defines the test pyramid and quality gates for Kotlin SMTP.

## 1. Test Pyramid

### Unit tests (fast, most of the suite)
- Target: parser/validation/policy/state transition units.
- Modules: `kotlin-smtp-core`, `kotlin-smtp-spring-boot-starter`, relay starters.
- Goal: deterministic behavior and quick feedback.

### Integration tests (protocol and wiring)
- Target: SMTP command flow, STARTTLS/AUTH, relay path, spool interactions.
- Modules: root `src/test/kotlin`, starter module integration tests.
- Goal: verify cross-module behavior and protocol correctness.

### Performance/benchmark tests (separate profile)
- Target: throughput/latency regressions under representative workload.
- Modules: `kotlin-smtp-benchmarks`.
- Goal: detect trend regressions, not absolute SLA guarantees.

## 2. Mandatory Quality Gates

- `./gradlew test`
- `./gradlew apiCheck`
- `./gradlew jacocoTestCoverageVerification`

CI executes `test` + `apiCheck` + `jacoco` verification on push and pull request.

## 3. Coverage Visibility

JaCoCo report generation is enabled for root and subprojects.

- `./gradlew jacocoTestReport`

Generated report locations:
- `<module>/build/reports/jacoco/test/html/index.html`
- `<module>/build/reports/jacoco/test/jacocoTestReport.xml`

Coverage thresholds are active in CI for core modules, starting from conservative baselines.
Thresholds are raised incrementally using the plan in `COVERAGE_ROADMAP.md`.

Current baseline thresholds (line coverage, bundle level):
- `kotlin-smtp-core`: >= 0.08
- `kotlin-smtp-spring-boot-starter`: >= 0.05
- `kotlin-smtp-relay-spring-boot-starter`: >= 0.05

Threshold raise plan is tracked in `COVERAGE_ROADMAP.md`.

## 4. Recommended Local Workflow

1. Run focused module tests for changed area
2. Run full `./gradlew test apiCheck jacocoTestCoverageVerification`
3. Generate coverage report when tests are added/changed
4. For performance-sensitive changes, run benchmark profile (`docs/PERFORMANCE.md`)

## 5. Priority Expansion Areas

- Core protocol edge-case unit tests (AUTH LOGIN/DSN/SIZE boundary additions ongoing)
- Spool failure injection scenarios (network/TLS/DNS failure classes)
- Relay security policy matrix tests (`trustAll`, `failOnTrustAll`, auth-required interactions)
