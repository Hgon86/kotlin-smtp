# Coverage Roadmap

This roadmap defines how Kotlin SMTP will raise test coverage gates over time.

## Current Gate (Phase 1)

Line coverage minimums are enforced for core modules:

- `kotlin-smtp-core`: `0.08`
- `kotlin-smtp-spring-boot-starter`: `0.05`
- `kotlin-smtp-relay-spring-boot-starter`: `0.05`

Reason:
- Start with realistic baselines that do not block active stabilization.
- Use CI visibility to identify low-coverage hotspots before aggressive thresholds.

## Planned Phases

### Phase 2 (next minor release)
- `kotlin-smtp-core`: `0.12`
- `kotlin-smtp-spring-boot-starter`: `0.08`
- `kotlin-smtp-relay-spring-boot-starter`: `0.08`

### Phase 3
- `kotlin-smtp-core`: `0.18`
- `kotlin-smtp-spring-boot-starter`: `0.12`
- `kotlin-smtp-relay-spring-boot-starter`: `0.10`

### Phase 4 (target for stable production hardening)
- `kotlin-smtp-core`: `0.25`
- `kotlin-smtp-spring-boot-starter`: `0.18`
- `kotlin-smtp-relay-spring-boot-starter`: `0.15`

## Raise Criteria

Move to next phase only when all conditions are met:

1. `./gradlew test apiCheck jacocoTestCoverageVerification` passes on `dev` for at least one sprint.
2. No increase in flaky tests across CI matrix.
3. New security/operations paths include focused tests.

## Priority Coverage Expansion

1. SMTP protocol state transitions in `kotlin-smtp-core`.
2. Spool retry/failure matrix in `kotlin-smtp-spring-boot-starter`.
3. Relay guardrail matrix in `kotlin-smtp-relay-spring-boot-starter`.

## Developer Workflow

- Generate reports: `./gradlew jacocoTestReport`
- Enforce gate locally: `./gradlew jacocoTestCoverageVerification`
- Full quality gate: `./gradlew test apiCheck jacocoTestReport jacocoTestCoverageVerification`
