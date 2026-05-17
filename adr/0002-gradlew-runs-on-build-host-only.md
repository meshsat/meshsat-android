# ADR-0002 — `./gradlew` runs on `nllei01androidsdk01`, NEVER locally

## Status

Accepted — 2026-05-17 (codifies CLAUDE.md P0 directive)

## Context

Android Gradle builds have known reproducibility issues:
- Local Gradle daemon caches non-determinism across runs.
- JDK version mismatches (project requires 17; many workstations have 21+).
- AGP / KSP / Compose version interactions are sensitive to local SDK install order.
- Signing keys + Play Store credentials are environment-bound (CI-only).
- Build outputs differ subtly between Linux/macOS workstations.

This produced "works on my machine" failures repeatedly. Fix: pin Gradle invocations to a single SDK host (`nllei01androidsdk01`, 192.168.181.101, VMID 101100106).

## Decision

ALL `./gradlew` invocations MUST be SSH'd to `nllei01androidsdk01`:

```bash
ssh ansible@nllei01androidsdk01 'cd /path/to/meshsat-android && ./gradlew <task>'
```

CI uses the same host as a registered runner. Operators do NOT run gradlew on their workstations.

## Consequences

**Positive:**
- Reproducible builds: identical output for identical input.
- One JDK, one SDK install, one Gradle version — no environment matrix.
- Signing config + Play Store creds live in one secure place.
- Workstation development is read/edit/git only — fast iteration without build infrastructure.

**Negative:**
- SSH latency adds ~2s per invocation.
- `nllei01androidsdk01` is a single point of failure for Android builds. Mitigated by VM snapshot + clone-on-demand procedure.
- New contributors must be granted SSH access before they can build. Documented in onboarding.

## Operational mechanics

- `gradle-7.6.4-bin.zip` cached on host.
- Android SDK at `/opt/android-sdk` (sym-linked to home for `ANDROID_HOME`).
- Build outputs in `/home/claude-runner/gitlab/products/cubeos/meshsat-android/app/build/outputs/` are SCP'd back to the operator's workstation if needed for inspection.
- Real-device validation: emulator runs on the same host; ADB through `localhost:5037`.

## Alternatives considered

- **Local builds with version pinning** (rejected — JDK + SDK version matrix is too brittle in practice)
- **Containerised build** (rejected — Docker on macOS adds another layer of variance; would still need a Linux build host for releases)
- **Cloud build (GitHub Actions / GitLab.com SaaS)** (rejected — Play Store secrets in cloud CI is a higher-risk posture than self-hosted)
