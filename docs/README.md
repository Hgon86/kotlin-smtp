# Kotlin SMTP Docs

Start here when you want to build, customize, and operate Kotlin SMTP quickly.

## Quick Navigation

- Getting started and modules: `../README.md`
- Runtime architecture: `ARCHITECTURE.md`
- Configuration reference: `CONFIGURATION.md`
- Migration guide: `MIGRATION.md`
- Release process: `RELEASE_PROCESS.md`
- Extension overview: `EXTENSION.md`
- Performance guide: `PERFORMANCE.md`
- Testing strategy: `TESTING_STRATEGY.md`
- Coverage roadmap: `COVERAGE_ROADMAP.md`
- 10-minute quickstart: `QUICKSTART.md`
- Operations runbook: `OPERATIONS.md`
- Operations templates: `templates/`
- Complete server assembly blueprint: `COMPLETE_SERVER_BLUEPRINT.md`

## I Want To...

- Understand local vs relay message flow
  - `LIFECYCLE.md`
- Know what to implement for each customization goal
  - `EXTENSION_MATRIX.md`
- Copy minimal extension examples
  - `RECIPES.md`
- Harden relay security and avoid open relay
  - `SECURITY_RELAY.md`
- Run production operations and incident response
  - `OPERATIONS.md`
- Plan a James-style replacement path
  - `COMPLETE_SERVER_BLUEPRINT.md`

## Recommended Reading Paths

### New users
1. `../README.md`
2. `QUICKSTART.md`
3. `CONFIGURATION.md`
4. `ARCHITECTURE.md`

### Customization and integration
1. `EXTENSION.md`
2. `EXTENSION_MATRIX.md`
3. `RECIPES.md`
4. `LIFECYCLE.md`

### Production hardening
1. `SECURITY_RELAY.md`
2. `CONFIGURATION.md`
3. `LIFECYCLE.md`
4. `OPERATIONS.md`

### Complete server composition
1. `ARCHITECTURE.md`
2. `EXTENSION.md`
3. `RECIPES.md`
4. `COMPLETE_SERVER_BLUEPRINT.md`

## Document Scope

- `ARCHITECTURE.md`: system boundaries and runtime model
- `CONFIGURATION.md`: complete YAML property guidance
- `MIGRATION.md`: user-facing migration steps between versions
- `RELEASE_PROCESS.md`: release quality gates and API-change policy
- `EXTENSION.md`: extension strategy and override rules
- `EXTENSION_MATRIX.md`: goal -> interface -> bean mapping
- `RECIPES.md`: minimal practical customization snippets
- `LIFECYCLE.md`: extension invocation timing
- `SECURITY_RELAY.md`: relay policy and TLS security guidance
- `PERFORMANCE.md`: benchmark workflow and reporting checklist
- `TESTING_STRATEGY.md`: test pyramid, quality gates, and coverage policy
- `COVERAGE_ROADMAP.md`: phased coverage threshold raise plan
- `OPERATIONS.md`: deployment templates, SLO/alerts, and incident playbooks
- `REVIEW_ROUNDS.md`: staged review findings and execution history
- `COMPLETE_SERVER_BLUEPRINT.md`: engine vs complete-product boundary and staged assembly roadmap
