# 3. Parallel-dev workflow override

Date: 2026-05-18

## Status

Accepted

## Context

Per CubeOS Article XV, the repo's default workflow is "push to main, CI auto-deploys." For parallel-dev waves the merge-coordinator opens one MR per feature instead.

## Decision

For parallel-dev waves only: `merge/<feature_id>` short-lived branch, one MR per feature, auto-delete on merge. Human work continues push-to-main.

## Consequences

Same as CubeOS-family ADR-0008 (parent). This file exists for symmetry with the other repos.
