# Public API Policy (kotlin-smtp-core)

This project is being prepared as a reusable library.
To keep evolution manageable, we explicitly separate "public API" (semver-stable) from "internal implementation".

## Scope

- This policy applies primarily to `kotlin-smtp-core`.
- `kotlin-smtp-spring-boot-starter` is a convenience integration layer; it may evolve faster.

## Public API Definition

Public API is any type/function that a host application must:

- construct/call directly, or
- implement to plug in behavior, or
- reference in configuration/wiring.

Initial candidates live in `docs/PUBLIC_API_CANDIDATES.md`.

## Recommended Package Rules

- Public packages should be few and intentional.
- Anything Netty/pipeline/decoder specific should be treated as internal until proven stable.

Suggested conventions:

- Public: `io.github.kotlinsmtp.*` (selected subpackages only)
- Internal implementation: `io.github.kotlinsmtp.internal.*` or `io.github.kotlinsmtp.impl.*`
- Kotlin visibility: prefer `internal` for non-API types even inside public packages.

## Compatibility Rules

- Changes to public API follow semver:
  - breaking change -> major
  - backwards-compatible feature -> minor
  - bugfix -> patch
- Internal packages may change without major bumps.

## Checklist When Changing Core

- Does the change affect a type listed in `docs/PUBLIC_API_CANDIDATES.md`?
- If yes, update this doc + ensure tests cover the behavior.
- If a type becomes stable and needed by users, promote it intentionally (document why).

## Java/Kotlin Considerations

The library is Kotlin but JVM-based; Java users can depend on it.
However, Kotlin-specific APIs (e.g., `suspend` functions, default args) can be awkward from Java.

If Java friendliness becomes a goal, add an explicit facade layer (non-suspending start/stop, builders) rather than exposing internals.
