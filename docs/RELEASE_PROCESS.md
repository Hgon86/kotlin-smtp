# Release Process

This checklist standardizes release quality gates and API-change documentation.

## 1. Required Build Checks

- `./gradlew test apiCheck jacocoTestReport jacocoTestCoverageVerification`

If performance-sensitive paths changed:
- `./gradlew :kotlin-smtp-benchmarks:jmh`
- `./gradlew :kotlin-smtp-benchmarks:performanceTest`

## 2. API Change Policy

If any API baseline file changes (`kotlin-smtp-core/api/*.api`, `kotlin-smtp-relay/api/*.api`):

1. Update `CHANGELOG.md` (`[Unreleased]` section)
2. Update `docs/MIGRATION.md` when user-facing action is required
3. Explain why the API change is needed in PR summary

## 3. Documentation Sync

At minimum, review:
- `README.md`
- `docs/CONFIGURATION.md`
- `docs/MIGRATION.md`
- `docs/OPERATIONS.md` (if behavior impacts operations)

## 4. Release Notes

Before tagging:

1. Move relevant `CHANGELOG.md` entries from `[Unreleased]` into versioned section.
2. Confirm migration notes are complete for that version.
3. Verify no API change landed without changelog note.
