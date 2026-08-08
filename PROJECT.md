# Project Charter — MeshSat Android

> Component-scoped charter. Parent project: `/home/claude-runner/gitlab/products/cubeos/docs/PROJECT.md` (CubeOS — Track B MeshSat sub-track). Authored 2026-05-18 against CGC ground truth.

## Role in the MeshSat family

`meshsat-android` (GitLab project 31) is the **third peer** in the MeshSat communications mesh. Each peer is first-class — none requires the others to operate:

- `meshsat/` (project 27) — Bridge: standalone Pi gateway with serial radios, satellite modems, mesh transports.
- `meshsat-android/` (this repo) — Mobile/tablet companion: standalone gateway over BLE + cellular + satellite SPP, OR paired controller of a Bridge.
- `meshsat-hub/` (project 35) — Cloud counterpart: multi-tenant SaaS for fleet management + situational awareness.

Inter-node protocol: `meshsat-uplink/v1` (Sparkplug-B-inspired BIRTH/DATA/DEATH over MQTT for Bridge↔Hub; Reticulum-based for Bridge↔Bridge; pair-shell HTTPS for Android↔Bridge).

## What this repo owns (CGC-verified scope as of 2026-05-18)

The current shipped feature set spans **28 top-level packages** under
`app/src/main/java/com/cubeos/meshsat/`, holding **194 Kotlin source files** (239 including
tests), **1.523 function declarations**, **360 class, object and interface declarations**,
41.906 lines, and **18 Room entities** (DB v14). Test suite: 519 `@Test` functions.

Recounted from the tree 2026-08-08, superseding the CGC-derived figures dated 2026-05-18 which
claimed 269 source files / 9.993 functions / 680 classes / 29 packages — the function count was
out by roughly six and a half times and the class count by about two. Recount before quoting any
of these externally.

1. **Transport adapters** — BLE Meshtastic (`ble/`), Iridium SPP over Bluetooth (`bt/`), APRS over KISS (`aprs/`), AX.25, SMS native (`sms/`), MQTT, TAK/CoT (`tak/`).
2. **Codec + crypto** — SMAZ2 (`codec/`), AES-GCM (`crypto/AesGcmCrypto.kt`), MSVQ-SC compression decoder (`crypto/MsvqscEncoder.kt` + tokenizer), Android Keystore (`crypto/SecureKeyStore.kt`), TOFU key-bundle import (`crypto/KeyBundleImporter.kt`).
3. **Routing engine** — Reticulum (`reticulum/`), HeMB heterogeneous media bonding (`hemb/`), DTN bundle store/forward (`dtn/`), FEC + RLNC (`fec/` + `rlnc/`), per-rule access filtering (`rules/`).
4. **Engine internals** — Dispatcher (`engine/Dispatcher.kt`), DeadManSwitch, GeofenceMonitor, HealthScorer, InterfaceManager, BurstQueue, CreditTracker, SequenceTracker, TelemetryLogger.
5. **Data layer** — Room database with 18 entities at version 14 (`data/AppDatabase.kt`), migrations `MIGRATION_5_6` through `MIGRATION_13_14`.
6. **Service layer** — Foreground GatewayService (`service/GatewayService.kt`) hosts transports; TransportRegistry tracks active adapters.
7. **Local REST API** — NanoHTTPD-based server at 127.0.0.1 (`api/LocalApiServer.kt`) for scripting + automation; port of the Bridge's `meshsat/internal/api/`.
8. **Compose UI** — 1 top-level orchestrator (`ui/MeshSatUI.kt`), 17 screens under `ui/screens/`, theme set under `ui/theme/`, components under `ui/components/`.

## What this repo does NOT own

- **Radio drivers** — those live on the Bridge (`meshsat/`) reachable via BLE pairing.
- **Multi-tenant management** — Hub (`meshsat-hub/`) owns tenant isolation.
- **TAK server** — companion app sends CoT via Hub proxy per ADR-0011 (not a direct TAK server).

## Constitutional inheritance

Inherits the full CubeOS project-level constitution + the MeshSat sub-family constitution. Component-specific articles cover the build host, BouncyCastle initialisation, Room migrations, Keystore-only private keys.

## Build (CGC-verified 2026-05-18)

```bash
# Build runs on a remote SDK host — see ADR-0002 for rationale
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:assembleDebug'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:testDebugUnitTest'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:lintDebug'
```

`app/build.gradle.kts`:
- `applicationId = "com.cubeos.meshsat"`
- `versionName = "2.8.6"` / `versionCode = 51` (2026-05 release line)
- `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- Kotlin JDK target = 17

## Test layout (CGC-verified)

All 45 existing tests live FLAT at `app/src/test/java/com/cubeos/meshsat/<Name>Test.kt`. No nested test packages. New tests for SDD-driven work MUST follow the same flat convention.

## Source trace

- `meshsat-android/CLAUDE.md` (local-only operator notes)
- Parent: `/home/claude-runner/gitlab/products/cubeos/docs/architecture/02_ARCHITECTURE.md` (CubeOS system architecture)
- Parent: `/home/claude-runner/gitlab/products/cubeos/meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md` (pair-shell protocol design)
- Parent: `/home/claude-runner/gitlab/products/cubeos/meshsat/EXECUTION-PLAN.md` (cross-cut Android stories under §6.5 + §6.8)
- CGC audit: `claude-gateway/docs/sdd-audits/meshsat-android-cgc-2026-05-18.md`
