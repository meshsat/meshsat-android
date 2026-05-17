# ADR-0003 — Parallel-dev workflow override

## Status

Accepted — 2026-05-17

## Context

CLAUDE.md final line: "Push directly to main. No branches, no MRs. Pipeline builds APK automatically." Parallel-dev fundamentally requires branches per worker + 1 MR per feature.

## Decision

Override "push to main" for parallel-dev waves ONLY:
1. Planner creates synthetic YT epic + N child tasks
2. Distribute allocates N worktrees on `parallel-dev/<feature_id>/<task_id>` branches
3. Workers commit to their own branches (local-only, never pushed)
4. Merge-coordinator creates `merge/<feature_id>` from origin/main, applies worker patches, runs `./gradlew check` (via SSH per ADR-0002), opens ONE MR
5. Auto-delete branch on merge

Direct human commits continue push-to-main as before.

## Consequences

**Positive:** parallel-dev available + auditable via single MR. Human velocity unchanged.
**Negative:** two workflows exist; documented in `steering/repo-conventions.md`.

## Android-specific notes

- The merge-coordinator's test step (`./gradlew check`) MUST go through SSH per ADR-0002. The `test_command` field in PROJECT.json reflects this.
- Worker `parallel-dev/*` branches that touch Room schema migrations (`data/AppDatabase.kt`) MUST be `parallelizable: false` (Constitution Article XIII). Forgetting this = corrupted Room schema-version chain.
- UI-screen-only work (e.g. MESHSAT-568 candidate) is the safest first parallel-dev surface — minimal cross-file collision risk.

## Alternatives

- Deny parallel-dev to Android (rejected — Compose UI work is naturally parallelizable + this app is the operator's biggest greenfield surface)
- Convert Android to MR-only (rejected — disruptive to existing solo-developer cadence)
