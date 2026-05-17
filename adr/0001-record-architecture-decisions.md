# ADR-0001 — Record architecture decisions

## Status

Accepted — 2026-05-17

## Context

MeshSat Android has accumulated tribal knowledge in CLAUDE.md (368 lines, gitignored). Three known production bugs traced to load-bearing constraints not visible in code: BouncyCastle ordering (MESHSAT-497), osmdroid NavHost exclusion (MESHSAT-479 lineage), `org.json.JSONObject` JVM-test silent-null trap.

## Decision

MADR format. One ADR per file under `adr/NNNN-<title>.md`. Status, Context, Decision, Consequences, Alternatives.

## Consequences

Positive: production-bug constraints become permanent guardrails for new contributors (human or agent). Every "why is this written this way" question has a searchable answer.

Negative: small discipline cost.

## Alternatives

- No ADRs (rejected — too easy to re-introduce known production bugs)
- Inline `// CAUTION:` comments (rejected — don't capture full reasoning)
