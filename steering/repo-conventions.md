# Steering — Repo conventions (MeshSat Android)

## Branch + workflow

- **Default:** push directly to main. No branches, no MRs. Pipeline builds APK + AAB + uploads to GitHub Releases / Play Store internal track automatically.
- **Parallel-dev exception** (Constitution Article XV, ADR-0003): ONE short-lived `merge/<feature_id>` branch per wave, ONE MR per feature opened by `merge-coordinator.sh`, auto-deleted on merge.
- Worker branches `parallel-dev/<feature>/<task>` are local-only intermediates — never pushed to remote.
- Snapshot branches: `snapshot/<YYYY-MM-DD>-<purpose>` for rollback.

## Commit messages

- Format: `type(scope): description [MESHSAT-NNN]`
- Type: `feat | fix | refactor | test | docs | chore | perf | build | ci | a11y`
- ALWAYS reference a YouTrack issue ID + (for Android-specific) tag `tag:android` on the issue.
- Workers' commits auto-append `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- Compose UI changes should reference the screen file (e.g. `feat(ui): one-tap team broadcast in MessagesScreen [MESHSAT-568]`).

## File layout

- Single Gradle module `:app`
- `app/src/main/java/com/cubeos/meshsat/` — production code organised by bounded context (28 packages)
- `app/src/main/AndroidManifest.xml` — permissions + service declarations
- `app/src/main/res/` — Compose theme tokens + drawable + raw assets
- `app/src/main/assets/` — bundled `world.mbtiles` + ONNX model files (no-compress flagged)
- `app/src/test/` — JVM unit tests (~45 files)
- `app/src/androidTest/` — **DOES NOT EXIST** (gap noted in test-strategy.md)
- `proto/` — 27 vendored `.proto` files (Meshtastic + TAK-CoT)
- `gradle/libs.versions.toml` — version catalog
- `proguard-rules.pro` — R8 keep rules (Article VII)
- `signing.properties.example` — template for signing config (real properties NOT committed)

## YouTrack discipline

- All 3 meshsat-family repos share the `MESHSAT` project. Disambiguation:
  - Bridge (`meshsat/`): no `tag:hub` AND no `tag:android` — default
  - Hub (`meshsat-hub/`): `tag:hub`
  - Android (THIS REPO): `tag:android` REQUIRED on every new issue
- Parallel-dev Planner uses tags to route correctly. Issues without disambiguator default to bridge.
- **Tag every new Android issue with `tag:android` at creation.** Mass-retag of historicals tracked separately.

## CI

GitLab pipeline:
- **lint:** `./gradlew lintDebug` (Android Lint)
- **test:** `./gradlew testDebugUnitTest` (JVM unit, ~400 tests)
- **build-debug:** `./gradlew assembleDebug` (sideload APK)
- **build-release:** `./gradlew assembleRelease bundleRelease` (signed APK + AAB)
- **distribute-github:** upload APK to GitHub Releases
- **distribute-play:** upload AAB to Play Store internal track (versionCode auto-increment)

All stages run on `nllei01androidsdk01` runner (registered tag: `android-sdk`).

## Pre-commit (developer-side via SSH)

```bash
ssh ansible@nllei01androidsdk01 'cd /path/to/meshsat-android && ./gradlew check'
```

`check` runs both unit tests + lint. ~3-5min wall-clock.

## Versioning

- `versionCode` = monotonically increasing integer (currently 51).
- `versionName` = SemVer (`v2.8.6`).
- Bump both on release-track changes. Bump only `versionCode` on internal-track-only pushes.

## Distribution

- **Primary:** GitHub Releases (sideload APK). End-users get update via in-app update check (operator-controlled).
- **Secondary:** Play Store internal track. Real promotion to production track requires operator manual escalation.
- Play Store has ~24h internal review queue — bad AAB blocks next release for that window.
