# 2. `gradlew` runs on `nllei01androidsdk01` only

Date: 2026-05-18 (codifying decision originally made 2026-01)

## Status

Accepted

## Context

Android builds need the Android SDK, JDK 17, ARM toolchains, AAPT2, kapt processors, and the Compose compiler. Setting these up on every developer workstation + every parallel-dev worker is fragile (version drift) and slow (multi-GB downloads). The `nllei01androidsdk01` LXC has the canonical toolchain already.

## Decision

All `./gradlew` invocations route via SSH to the SDK host:

```bash
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew <task>'
```

This applies to: assembleDebug, assembleRelease, testDebugUnitTest, lintDebug, etc. Local-workstation gradle is NOT supported.

## Consequences

**Positive:**
- Single canonical toolchain version → reproducible builds.
- Workers don't need to mirror SDK install.
- Operator can swap SDK versions in one place.

**Negative:**
- SSH overhead per gradle call (~1-2s).
- Build host becomes a single point of failure for builds. Mitigation: it's an LXC; snapshots + restores trivial; cluster-wide PVE storage means it can run on any node.

**Enforced by:** Component Article C-I + `PROJECT.json` `test_command` / `build_command` / `lint_command` fields.
