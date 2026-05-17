# Project Charter — MeshSat Android

> Operator-facing charter. Vision, mission, scope, deliverables, success metrics. Authored 2026-05-17 by drawing from the strategic documents listed in **Source Trace** at the foot of this file. Machine-readable companion: `PROJECT.json`. Hard rules: `constitution.md`. Decisions: `adr/`.

---

## Vision

A **standalone field-gateway Android app** that turns a phone into a self-contained multi-transport mesh + satellite + tactical gateway — no Pi, no server dependency, no internet required. The phone **IS** the gateway; MeshSat Android is not a companion app for the Pi5 bridge but a peer node with adapter parity, capable of operating fully autonomously OR as part of the MeshSat mesh (Bridge + Android + Hub).

Source: `README.md` L1–L8 ("Native Android app that turns a phone into a standalone field gateway... The phone IS the gateway. No companion app, no server dependency, no internet required."), `docs/ARCHITECTURE.md` L5 (peer mesh principle).

## Mission

Implement the full MeshSat protocol stack — **6 transports + Reticulum Transport Node + 10+ Reticulum interfaces + AES-256-GCM + MSVQ-SC semantic compression + Ed25519 audit log + delivery ledger + access rules + field intelligence** — in Kotlin / Jetpack Compose against the same conceptual data flow as the Go Bridge (`docs/ARCHITECTURE.md` §"Shared Design Patterns"). When paired with a Bridge or Hub, MeshSat Android is a first-class peer; when alone in the field, it's a complete gateway.

Source: `README.md` L6, L120–L149 (Android vs Bridge Parity matrix).

## Scope (in / out)

**In scope — what this app does:**

1. **6 transports**: Meshtastic LoRa via BLE, Iridium 9603N SBD via HC-05 Bluetooth SPP (19200 baud), Iridium 9704 IMT via HC-05 SPP (230400 baud, 100 KB messages), APRS via KISS TNC + APRS-IS, native Android SMS (AES-GCM + MSVQ-SC), MQTT via Eclipse Paho (mTLS). (Source: `README.md` L80–L88)
2. **Reticulum Transport Node** with **10+ interfaces**: Meshtastic BLE, Iridium SBD, Iridium IMT, SMS, MQTT, TCP/HDLC (RNS interop), BLE peripheral (GATT server), Tor (SOCKS5), WireGuard, mesh, APRS. Cross-interface relay, announce propagation, hop counting, path discovery, AES-256-GCM encrypted links. (Source: `CLAUDE.md` L310–L331, `README.md` L150–L160)
3. **MSVQ-SC semantic compression** — Multi-Stage Vector Quantization, lossy, ~92% byte savings. **ONNX Runtime INT8 encoder** for TX path, **pure Kotlin codebook decoder** for RX path (RX never needs ML runtime). Auto-detection on RX. (Source: `README.md` L172–L176, `CLAUDE.md` L124–L131)
4. **End-to-end encryption** — AES-256-GCM per-conversation, Ed25519 signing + X25519 encryption identity, Master-key envelope via `EncryptedSharedPreferences` (AndroidX Security + Android Keystore hardware), QR key bundles (`meshsat://key/` URI) with **TOFU pinning** via `bridge_trust` Room table (MESHSAT-495). Bundle v2 embeds Ed25519 signing pubkey in signed payload — pubkey cannot be swapped without invalidating the signature. (Source: `README.md` L170–L195)
5. **Hub integration** — mTLS client certificates, all 8 Hub commands (ping, send_text, send_mt, flush_burst, config_update, reboot, credential_push, credential_revoke), HubReporter health telemetry, QR provisioning, receives TAK CoT positions via MQTT broadcast topic `meshsat/broadcast/tak/cot/in`. (Source: `README.md` L264–L271, `CLAUDE.md` L221–L227)
6. **14 Compose screens** — reorderable Dashboard (sparklines, SOS, location, queue depth, Reticulum widget, credit gauge), Comms (message tabs), Peers table, native osmdroid Map (markers + tracks + TAK diamonds + GPS), SGP4 Pass Predictor (Canvas bezier arcs), Interface management, Rules editor + DLQ Inspector, Audit log (Ed25519-signed entries with chain verification), Credentials, Settings, Radio Config (7-tab Meshtastic device configuration), Mesh Topology. (Source: `README.md` L274–L288)
7. **Local REST API** — NanoHTTPD on `localhost:6051`, 14 endpoints. Used by Hub-controlled automation + E2E tests. (Source: `README.md` L243–L253)
8. **Field intelligence** — Dead Man Switch (auto-SOS after configurable inactivity), Geofence Monitor (polygon zones with ray-casting + enter/exit events), Canned Codebook (30 military brevity messages, 2-byte wire format), Position Codec (16-byte full + 11-byte delta with DeltaEncoder), Smart APRS beaconing (corner-pegging). (Source: `README.md` L257–L261)
9. **Multi-instance transports** — `TransportRegistry` (ConcurrentHashMap) supports e.g. two HC-05 adapters for 9603N + 9704 simultaneously. (Source: `README.md` L239)
10. **Release pipeline** — Signed APK + AAB via OpenBao secret management (GitLab JWT → OpenBao auth → keystore fetch). Distribution via GitHub Releases (APK sideload, primary) + Play Store (AAB, secondary). (Source: `README.md` L205–L225, `CLAUDE.md` L64–L70)

**Out of scope — what this app is NOT:**

- **Not a Go binary** — Go code (serial AT drivers, USB hotplug, `direct_mesh.go`, JSPR helper subprocess) belongs in [`meshsat/`](../meshsat/) (project 27). Source: `CLAUDE.md` L9–L11, `constitution.md` Article I.
- **Not a SaaS server** — multi-tenant features (OAuth2/OIDC, RBAC, API keys, tenant isolation, NATS bus, MariaDB Galera) belong in [`meshsat-hub/`](../meshsat-hub/) (project 35). Source: `CLAUDE.md` L9–L10.
- **Not a TAK server** — TAK CoT XML server mode (bidirectional TAK gateway) is Bridge-only. Android is **receive-only** via Hub broadcast at `meshsat/broadcast/tak/cot/in`. Source: `README.md` L227–L231 (explicit Bridge/Android split for TAK).
- **Not Webhooks-emitting** — outbound HTTP webhooks are Bridge-only. Android has no webhook channel. Source: `README.md` L131 (Android column blank for Webhooks).
- **Not HeMB-emitting (yet)** — HeMB heterogeneous media bonding (RLNC) is currently Bridge-only. Android is on the roadmap. Source: `README.md` L132.
- **Not ZigBee** — ZigBee USB dongle (CC2652P + Z-Stack ZNP) is Bridge-only — phones don't carry ZigBee USB dongles. Source: `README.md` L128.
- **Not a local Google-Play-Services dependency** — operates without GMS in hardened environments; crash capture uses local `UncaughtExceptionHandler` + ring-buffer Room table, NOT Firebase/Crashlytics/Sentry. Source: `constitution.md` Article XII, `CLAUDE.md` L88.

## Current production state (as of 2026-05-17)

| Dimension | State | Source |
|---|---|---|
| Version | **v2.8.6** (versionCode 51, released 2026-04-05) | `PROJECT.json#current_version`, `CLAUDE.md` L86 |
| Distribution | **GitHub Releases (APK sideload, primary)** + **Play Store (AAB, secondary)** | `PROJECT.json#distribution`, `README.md` L29 |
| Signing | **PKCS12 RSA 2048, 30-year validity, OpenBao vault** at `secret/ci/android-signing`; upload key alias `meshsat-upload`. SHA-256: `8ca78b6c33bd9796bb05f40fec2a0ab801e0297e7565960d42f5e6af821c9f66` | `README.md` L205–L223 |
| Ecosystem grade | **82/100 (B+)** per 2026-04-04 audit. 539 tests, 49K LOC Kotlin (28 modules), 5-phase parity (A-E) complete + all 16 backend parity gaps closed (MESHSAT-385) + GUI clone Phases M-P complete | `docs/PRODUCTION_READINESS_AUDIT_2026-04-04.md` §2.3, `CLAUDE.md` L271–L361 |
| Room schema | **v14** (telemetry table added MESHSAT-494) | `CLAUDE.md` L182, `data/AppDatabase.kt` |
| Test surface | ~550 JVM unit tests; **NO `androidTest/` directory** — instrumented tests not in CI | `PROJECT.json#test_infrastructure_gaps`, `CONTRIBUTING.md` L8–L11 |
| Build host | **`nllei01androidsdk01`** (192.168.181.101, VMID 101100106) — `./gradlew` MUST NOT run locally | `PROJECT.json#build_host_required`, `CLAUDE.md` L29–L51, ADR-0002 |
| Primary phone for E2E | **Google Pixel 9a / Android 16 (API 36)** via wireless ADB | `README.md` L306, `CLAUDE.md` L99–L108 |

## Active priorities (next 4–8 weeks)

The active roadmap is driven by Bridge's `EXECUTION-PLAN.md` Phase 5 (Android directory sync + contact QR handoff, 3 stories, ~1 week) and Phase 8 (Pair protocol v1 + Android pair shell, 15 stories, ~3 weeks). Android-specific tasks within those:

| Story | From Bridge Phase | Goal | Repo |
|---|---|---|---|
| S5-01 | 5 | Android pulls directory snapshot from Bridge over HTTPS into Room (v-NEXT mirrors v44-v47) | meshsat-android |
| S5-02 | 5 | Android People view native Compose UI | meshsat-android |
| S5-03 | 5 | Android QR contact card handoff (standalone ↔ paired) | meshsat-android |
| S8-07 | 8 | Android three-way splash: Standalone / Pair / Both | meshsat-android |
| S8-08 | 8 | Android QR scanner (CameraX + ZXing) | meshsat-android |
| S8-09 | 8 | Android pair-claim client (ECDH + HMAC + CSR + TLS SPKI pinning, Android Keystore-backed) | meshsat-android |
| S8-10 | 8 | Android `paired_bridges` Room DB + repository | meshsat-android |
| S8-11 | 8 | Android **hybrid shell**: native Compose (hot paths) + WebView (Settings + Engineer views, cert-pinned, JWT-injected) | meshsat-android |
| S8-12 | 8 | Android "Both" mode — Source switcher UI + per-source data partitioning | meshsat-android |

Source: `meshsat/EXECUTION-PLAN.md` §6.5 + §6.8.

**Plus production-readiness items from `docs/PRODUCTION_READINESS_AUDIT_2026-04-04.md` §4 still open:**
- (C2) Add test + lint to Android CI (~2h, +2 audit points)
- (M3) R8/ProGuard release-size optimization (debug APK is 129 MB; production should target <50 MB)
- (M4) Local crash-reporting wiring (already implemented v2.8.6 via local ring buffer — opt-out via `telemetryEnabled` pref; no external service per Article XII)

## Success metrics

| Dimension | Metric | Source |
|---|---|---|
| Build reproducibility | Every release-tag build via OpenBao reproduces the same APK signing fingerprint (`8ca78b6c…`) | `README.md` L220 |
| Signing | `apksigner verify --print-certs` returns Signer #1 CN=MeshSat, OU=CubeOS, O=Nuclear Lighters on all v2.8.0+ releases | `README.md` L214–L223 |
| Reticulum interop | TCP/HDLC handshake with stock Python `rns` reference implementation green; cross-bridge Bundle v2 import succeeds (MESHSAT-208/267-272) | `constitution.md` Article XI, `CLAUDE.md` L313–L331 |
| Crash recovery | `UncaughtExceptionHandler` writes `filesDir/pending_crash.json` synchronously; `TelemetryLogger.recoverPendingCrashes()` migrates to DB on next startup (ACRA pattern, E2E verified via `am crash`) | `CLAUDE.md` L89 |
| Foreground service survival | `START_STICKY` brings GatewayService back after OOM kill; transport state reconciles via `InterfaceManager` 5-state machine | `constitution.md` Article VIII |
| Telemetry | Periodic samplers: heap every 5 min, health heartbeat every 60s, ring buffer capped per type via `trimType` on insert | `CLAUDE.md` L89 |
| Test discipline | All transport-touching changes require real-hardware test before PR; ~550 unit tests must pass before push (`./gradlew testDebugUnitTest`) | `CONTRIBUTING.md` L40–L43, `README.md` L455 |

## Non-goals

- **No external telemetry / crash service** — no Firebase / Crashlytics / Sentry / FCM / GMS dependency. Local-only ring buffer with operator-controlled REST pickup. Source: `constitution.md` Article XII, `CLAUDE.md` L88.
- **No bidirectional TAK server** — TAK CoT is receive-only via Hub broadcast. Source: `README.md` L231.
- **No HeMB encoding** — Android currently consumes single-bearer; HeMB bonding is Bridge-only today. Source: `README.md` L132.
- **No replacement of Bridge** — Android + Bridge are peer nodes (`docs/ARCHITECTURE.md` L5). Same conceptual interface; different runtime constraints (battery, OS sandboxing, store distribution).
- **No KMP shared module** — Android (Kotlin) and Bridge (Go) share **conceptual interface** via the parity matrix, NOT compiled-code symbol surface. Two implementations, one design contract. Source: `docs/ARCHITECTURE.md` §"Shared Design Patterns" + `README.md` L120–L149.
- **No `gradlew` execution on operator workstations** — always SSH to `nllei01androidsdk01`. Source: `constitution.md` Article II, ADR-0002.

## Stakeholders

- **Owner / operator:** `ufwtqkgz@meshsat.net` (sole operator, same ID owns `meshsat` + `meshsat-hub`).
- **Test phone:** Google Pixel 9a (Android 16 / API 36, BouncyCastle/Conscrypt verified).
- **Build host:** `nllei01androidsdk01` (192.168.181.101, VMID 101100106 on `nllei01pve01`).
- **Distribution:** GitHub Releases (APK, primary) + Play Store (AAB, secondary).
- **Compliance / signing key custodian:** OpenBao vault at `secret/ci/android-signing`. Loss of this keystore = loss of in-place upgrade path for all v2.8.0+ users. **Backup posture is operator-critical.**
- **Adjacent projects:**
  - [`meshsat`](../meshsat/) — Go bridge (parity peer).
  - [`meshsat-hub`](../meshsat-hub/) — SaaS fleet management (mTLS peer).
  - [Meshtastic](https://meshtastic.org) — official protobuf bindings consumed via `meshtastic/protobufs`.
  - [Reticulum Network Stack](https://reticulum.network) — wire-format reference; cross-interop validated.
  - [Direwolf KISS](https://github.com/wb2osz/direwolf) — APRS modem upstream.
  - [osmdroid](https://github.com/osmdroid/osmdroid) — Apache 2.0 native OSM tile renderer (chosen over WebView/Leaflet).
  - [ONNX Runtime Android](https://onnxruntime.ai/) — INT8 quantized MSVQ-SC sentence encoder.

## Architectural pillars (linked artifacts)

| Pillar | Authoritative source |
|---|---|
| Identity boundary (phone IS the gateway, NOT a companion) | `constitution.md` Article I + `docs/ARCHITECTURE.md` (peer mesh) |
| `gradlew` runs on `nllei01androidsdk01` only | `constitution.md` Article II + `adr/0002-gradlew-runs-on-build-host-only.md` |
| BouncyCastle provider ordering (`addProvider`, NOT `insertProviderAt(BC, 1)`) | `constitution.md` Article III (load-bearing per MESHSAT-497) |
| osmdroid `MapScreen` OUTSIDE `NavHost` | `constitution.md` Article IV (osmdroid `MapView` cannot survive `onDetachedFromWindow`) |
| Never call `MapView.zoomToBoundingBox()` (ANR > 5s) | `constitution.md` Article V (MESHSAT-479) |
| JVM-test-safe imports (`java.util.Base64`, `BirthSigner.toCanonicalJson`) | `constitution.md` Article VI |
| R8 keep-rules per reflection/JNI dep | `constitution.md` Article VII |
| Foreground service hygiene + permission-gated start | `constitution.md` Article VIII |
| Offline-first | `constitution.md` Article IX |
| Local REST API surface on `localhost:6051` is part of the contract | `constitution.md` Article X (Hub automation + E2E tests depend on it) |
| Reticulum wire format preservation | `constitution.md` Article XI |
| No external telemetry (Firebase / Crashlytics / Sentry / FCM forbidden) | `constitution.md` Article XII (MESHSAT-494 local ring buffer) |
| Single Room migration per version (NEVER parallelize `AppDatabase.kt`) | `constitution.md` Article XIII |
| Compose state correctness (rememberSaveable / state-holder ViewModels) | `constitution.md` Article XIV |
| Parallel-dev workflow exception | `constitution.md` Article XV + `adr/0003-parallel-dev-workflow-override.md` |
| Adapter parity with Bridge (same conceptual interface, language-idiomatic impl) | `adr/0004-adapter-parity-with-bridge.md` + `docs/ARCHITECTURE.md` §"Shared Design Patterns" |
| Reticulum Transport Node (10+ interfaces, cross-interface relay) | `adr/0005-reticulum-transport-node-android.md` + `CLAUDE.md` L310–L331 |
| MSVQ-SC compression (ONNX TX, pure-Kotlin RX) | `adr/0006-msvqsc-compression-onnx-tx-pure-kotlin-rx.md` + `crypto/` package |
| Hybrid Compose + WebView pair shell (Phase 8) | `adr/0007-hybrid-compose-plus-webview-pair-shell.md` + `meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md` §5.2 |
| TOFU + Bundle v2 key pinning | `adr/0008-tofu-bundle-v2-key-pinning.md` + `README.md` L183–L194 |
| Release signing via OpenBao | `adr/0009-release-signing-via-openbao.md` + `README.md` L205–L225 |
| GUI parity with Bridge SPA (Phases M-P) | `adr/0010-gui-parity-with-bridge-spa.md` + `GUI-PARITY-SPEC.md` |

## Where decisions live

| Type of change | Where it gets recorded |
|---|---|
| New architectural commitment | New `adr/NNNN-<slug>.md` (MADR); link from this charter's pillar table |
| Tightening / loosening a security rule | New ADR + edit to relevant `constitution.md` Article |
| New transport | Update `README.md` Transports table + Android-vs-Bridge parity matrix + `CLAUDE.md` Architecture section + integration tests in `CONTRIBUTING.md` |
| Reticulum interface change | Edit `reticulum/` source + ensure stock-RNS interop test still passes; ADR if wire format changes |
| Phase 5 / Phase 8 work | Tracked in `meshsat/EXECUTION-PLAN.md` (the cross-repo plan); this charter's "Active priorities" reflects it |
| GUI parity gap | `GUI-PARITY-SPEC.md` (per-screen) + Compose implementation |
| Release | `README.md` Changelog + `CLAUDE.md` Recent release history (bump versionCode/versionName, tag `vX.Y.Z`, push) |
| Build/test infra (e.g. CI gating) | `.gitlab-ci.yml` + `docs/PRODUCTION_READINESS_AUDIT_2026-04-04.md` follow-up |

## What this charter is NOT

- **Not** the per-screen GUI spec — see `GUI-PARITY-SPEC.md`.
- **Not** the Android-vs-Bridge architecture rationale — see `docs/ARCHITECTURE.md` (peer mesh + shared design patterns).
- **Not** the production readiness audit — see `docs/PRODUCTION_READINESS_AUDIT_2026-04-04.md`.
- **Not** the build / SDK / Android-version gotcha catalog — see `CLAUDE.md` (gitignored ops notes: Android 14+/16 quirks, build-host SSH, OpenBao auth flow).
- **Not** the contributor onboarding doc — see `CONTRIBUTING.md`.

This charter is the load-bearing summary so an incoming engineer (human or agent) understands WHAT MeshSat Android is, WHY it exists, and WHERE the canonical source for each operational decision lives.

---

## Source Trace

| Statement in charter | Source file | Line range |
|---|---|---|
| Vision: phone IS the gateway, no companion | `README.md` | L1–L8 |
| Peer-mesh mission (Bridge + Android + Hub) | `docs/ARCHITECTURE.md` | L5–L8 |
| 6 transports enumerated | `README.md` | L80–L88 |
| Reticulum 10+ interfaces | `CLAUDE.md` | L310–L331, `README.md` L150–L160 |
| MSVQ-SC ONNX TX / pure-Kotlin RX | `README.md` | L172–L176, `CLAUDE.md` L124–L131 |
| AES-256-GCM + Master-key envelope + TOFU v2 | `README.md` | L170–L195 |
| Hub integration + 8 commands + mTLS | `README.md` | L264–L271, `CLAUDE.md` L221–L227 |
| 14 Compose screens | `README.md` | L274–L288 |
| Local REST API 14 endpoints | `README.md` | L243–L253 |
| Field intelligence (deadman, geofence, canned, position codec, smart beaconing) | `README.md` | L257–L261 |
| Multi-instance TransportRegistry | `README.md` | L237–L239 |
| Release pipeline via OpenBao | `README.md` | L205–L225, `CLAUDE.md` L64–L70 |
| Android-vs-Bridge parity matrix | `README.md` | L120–L149 |
| TAK receive-only via Hub broadcast | `README.md` | L227–L231 |
| HeMB bridge-only (today) | `README.md` | L132 |
| ZigBee bridge-only | `README.md` | L128 |
| Phase A-G + H-L + M-P parity history | `CLAUDE.md` | L271–L361 |
| 16-gap Bridge parity closure (MESHSAT-385) | `CLAUDE.md` | L340–L361 |
| Current v2.8.6 release | `CLAUDE.md` | L86 |
| Build host `nllei01androidsdk01` | `CLAUDE.md` | L29–L51 |
| Test surface ~550 JVM unit tests | `CONTRIBUTING.md` | L46–L48, `README.md` L455 |
| Production audit 82/100 grade | `docs/PRODUCTION_READINESS_AUDIT_2026-04-04.md` | §2.3 |
| GUI parity gap catalog | `GUI-PARITY-SPEC.md` | (full document) |
| Bridge Phase 5 + 8 driving Android work | `meshsat/EXECUTION-PLAN.md` | §6.5, §6.8 |
| Android 16 BouncyCastle / Conscrypt lesson | `README.md` | L356, `CLAUDE.md` L97–L108 |
| Crash recovery via UncaughtExceptionHandler | `CLAUDE.md` | L89 |
| Room schema v14 + 18 entities | `CLAUDE.md` | L182, `README.md` L400–L406 |
| Distribution: GitHub Releases (primary) + Play Store (secondary) | `PROJECT.json` `#distribution`, `README.md` L29 |
| Apache 2.0 license | `README.md` | L462 |
