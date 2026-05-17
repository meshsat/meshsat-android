# ADR-0009 — Release signing via OpenBao secret management (GitLab JWT → OpenBao auth → keystore fetch)

* Status: Accepted — codified after the fact 2026-05-17. Shipped v2.8.0 (MESHSAT-492); upgrade-in-place verified across every subsequent release through v2.8.6.
* Date: 2026-04 (decision + implementation); 2026-05-17 (ADR recorded).
* Deciders: `ufwtqkgz@meshsat.net`
* Source: `README.md` L205–L225, `CLAUDE.md` L62–L96

## Context

A signed Android APK is required for:
- **Play Store distribution** (mandatory upload key, > 25-year validity)
- **GitHub-Releases sideload** (so users can install without Play Protect blocking each version)
- **In-place upgrades** (Android refuses to install a new APK over an old one unless they're signed with the same key)

The signing keystore is therefore both a **production secret** (whoever has it can ship malicious updates that auto-install over genuine MeshSat) and an **append-once asset** (rotating it forces all installed-base users to uninstall and reinstall — losing data unless they back up first).

Two naive options:
1. **Keystore committed to the repo** (encrypted with a passphrase): rejected outright — putting a production signing keystore in git is wrong regardless of encryption.
2. **Keystore on the build host, manually managed**: works for one developer; doesn't survive build-host failure or operator transition. Manual restore from offline backup means hours of downtime + risk of mis-paste.

A managed secret store with CI-issuable short-lived tokens is the conventional answer. OpenBao (the open-source successor to HashiCorp Vault) is the chosen store.

## Decision

**Release-signing keystore lives in OpenBao** at `secret/ci/android-signing`. **GitLab CI authenticates via JWT** (`id_tokens`) → OpenBao's `auth/jwt/login` endpoint → fetches keystore bytes + passwords → builds + signs the APK + AAB → publishes to GitLab Package Registry + GitHub Releases.

### Keystore properties (per `README.md` L207–L209)
- **PKCS12** format
- **RSA 2048** key
- **30-year validity** (Play Store requires > 25 years)
- **Upload key alias:** `meshsat-upload`
- **Certificate DN:** `CN=MeshSat, OU=CubeOS, O=Nuclear Lighters, L=Leiden, ST=South Holland, C=NL`
- **SHA-256 fingerprint:** `8ca78b6c33bd9796bb05f40fec2a0ab801e0297e7565960d42f5e6af821c9f66`
- **SHA-1 fingerprint:** `9040570a7bf4d33890bf82ad85a7debf24fa57ab`

### CI pipeline (per `CLAUDE.md` L79–L85)
- `build-debug` (on main pushes): builds debug APK for verification.
- `build-release` (on tag pushes): OpenBao auth → fetches keystore → signed APK + AAB.
- `upload-package`: uploads both to GitLab Package Registry.
- `create-gitlab-release`: creates GitLab release with download links.
- `create-github-release`: creates GitHub release with APK + AAB assets.

### Gradle integration
- `app/build.gradle.kts` `signingConfigs` block reads from env vars: `ANDROID_KEYSTORE_FILE`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Without env vars (local dev): release builds use debug-signed APK as fallback. Operator never has the production keystore on their workstation.
- Verify locally with `apksigner verify --print-certs meshsat-android-X.Y.Z-release.apk` — the expected SHA-256 (`8ca78b6c...`) is documented in `README.md` for users to cross-check.

### Tag-driven release flow
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit version bump: `chore: bump version to vX.Y.Z`.
3. Tag: `git tag -a vX.Y.Z -m "MeshSat Android vX.Y.Z"`.
4. Push: `git push && git push origin vX.Y.Z`.
5. CI does the rest.

## Consequences

**Positive**
- Keystore never touches a developer workstation. Loss of `nllei01androidsdk01` doesn't lose the keystore — OpenBao is the source of truth.
- Tag-driven release flow means a successful release is one `git push origin vX.Y.Z` — minimal operator surface, minimal mistake space.
- The public fingerprint in `README.md` lets any user verify their downloaded APK with `apksigner` — third-party transparency for a self-hosted signing model.
- 30-year validity is past the operator's intended project horizon — no scheduled rotation pressure.
- All v2.8.0+ releases share the upload key — in-place upgrades work without uninstalling.

**Negative**
- **Loss of the OpenBao keystore = loss of in-place upgrade path for the entire v2.8.0+ installed base.** Operators would have to uninstall + reinstall. The OpenBao backup posture is operator-critical and MUST be treated as a Tier-1 data backup.
- The CI pipeline depends on OpenBao being reachable from the GitLab runner — OpenBao outage = no signed releases. Mitigation: debug-signed builds still work locally for emergency hotfix verification before pushing a tag.
- The JWT-based auth requires the GitLab runner to present a valid `id_tokens` payload — misconfiguration of the runner identity is a recoverable but visible failure mode.
- Anyone with read access to `secret/ci/android-signing` on OpenBao can sign a malicious APK. Mitigation: OpenBao's ACLs scope read access to the CI service account only; human operators authenticate separately.

**Forward direction**
- A planned "signed release manifest" — a checksums + signatures file alongside the GitHub Release assets — could let downstream auto-update tools verify integrity without `apksigner`. Out of scope for v2.8.6.
- Future Play Store App Signing migration would split upload-key (kept here) from app-signing-key (managed by Google). Reconsider when MeshSat reaches Play Store production track.

## Alternatives considered

- **Keystore committed (encrypted) to git**: rejected outright — even encrypted, a production signing key in git is a category error.
- **Keystore on build host filesystem, manual rotation**: rejected — single point of failure; loss of build host = loss of keystore.
- **Use Play App Signing exclusively** (Google manages the app-signing key): rejected as primary path — locks in Google ownership of the signing key; doesn't help the GitHub Releases sideload path; reconsider if/when MeshSat goes to Play Store production track.
- **Self-signed keystore on a hardware token (Yubikey)**: considered — operationally elegant; rejected as YAGNI for now (the OpenBao path serves; revisit if it doesn't).

## Compliance

- The OpenBao keystore at `secret/ci/android-signing` MUST have a tested restore procedure documented in operator-runbook form (not just "it's in OpenBao") — loss recovery is the load-bearing case.
- `apksigner verify --print-certs` on every release MUST produce the documented SHA-256 fingerprint `8ca78b6c...` — CI MUST fail the release if it doesn't.
- Bump versionCode strictly monotonically. Android refuses to install an APK with a lower versionCode than the installed version — non-monotonic bumps brick the upgrade path.
- The signing config in `app/build.gradle.kts` MUST refuse to ship if env vars are unset AND build type is "release" AND the build environment is not GitLab CI — never let a developer accidentally ship a debug-signed "release."
- `proguard-rules.pro` MUST stay in sync with new reflection / JNI deps per Constitution Article VII — a release build that runs in debug but crashes in release because of missing keep rules is a class of regression this signing pipeline cannot catch.
