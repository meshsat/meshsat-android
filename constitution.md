# Constitution — MeshSat Android (project 31)

19 non-negotiable principles for the standalone Android field-gateway mobile app + Reticulum Transport Node. These bind every contributor — human or agent.

> Companion documents: vision/mission/scope live in [`PROJECT.md`](PROJECT.md). Decisions live in `adr/`. Cross-platform parity contract in [`../meshsat/docs/ARCHITECTURE.md`](../meshsat/docs/ARCHITECTURE.md) §"Shared Design Patterns". GUI-parity spec at [`GUI-PARITY-SPEC.md`](GUI-PARITY-SPEC.md). Build/test/SDK gotchas in `CLAUDE.md` (gitignored).

## Article I — Identity boundary (NON-NEGOTIABLE)

The system shall be the **standalone Android mobile gateway AND full Reticulum Transport Node** — **the phone IS the gateway in the field**, NOT a companion to the Pi5 bridge. Its scope is the union of:

- **6 transports**: Meshtastic LoRa via BLE, Iridium 9603N SBD via HC-05 Bluetooth SPP, Iridium 9704 IMT via HC-05 SPP (230400 baud, 100 KB messages), APRS (KISS TNC + APRS-IS), native Android SMS (AES-GCM + MSVQ-SC), MQTT (Eclipse Paho + mTLS). (Source: `README.md` L80–L88)
- **10+ Reticulum interfaces** + **full Transport Node** (announce relay, cross-interface forwarding, path discovery, AES-256-GCM encrypted links) — see Article XVII / ADR-0005.
- **MSVQ-SC semantic compression** with **ONNX TX + pure-Kotlin RX** asymmetric runtime — see Article XVIII / ADR-0006.
- **Hub integration**: mTLS, all 8 Hub commands (ping/send_text/send_mt/flush_burst/config_update/reboot/credential_push/credential_revoke), QR provisioning, TAK CoT reception via MQTT broadcast.
- **14 Compose screens**, **local REST API on `localhost:6051`** (14 endpoints), field intelligence (DeadManSwitch / Geofence / CannedCodebook / PositionCodec / smart APRS beaconing), `TransportRegistry` multi-instance support.

The conceptual contract with Bridge is the **adapter parity** matrix (Article XVI / ADR-0004): same conceptual interface, language-idiomatic implementations. Go code belongs in `meshsat/` (project 27). Hub multi-tenant SaaS code belongs in `meshsat-hub/` (project 35). TAK CoT XML server mode, Webhooks, HeMB encoding, and ZigBee are Bridge-only by design (`README.md` L120–L149 parity matrix).

## Article II — `./gradlew` runs on `nllei01androidsdk01` ONLY

The system shall execute every Gradle invocation via `ssh ansible@nllei01androidsdk01 "cd <repo> && ./gradlew ..."`. Local gradlew runs are FORBIDDEN — they break workstation environments and produce non-reproducible artifacts. CI enforces.

## Article III — BouncyCastle provider ordering is load-bearing

The system shall add BouncyCastle via `Security.addProvider(BouncyCastleProvider())` — which appends at LOWEST priority. **NEVER `insertProviderAt(BC, 1)`** — it hijacks `SSLContext.getInstance("Default")` and silently breaks every HTTPS call (Hub TLS, map tiles, mTLS) until network actually starts, then throws `NoSuchAlgorithmException` buried in `SocketException`. All Ed25519/X25519 callers MUST pass `"BC"` explicitly.

## Article IV — osmdroid `MapScreen` MUST live OUTSIDE NavHost

The system shall keep `MapScreen` outside `NavHost`, hidden via `alpha(0)` when other tabs are active. **NEVER move it back into NavHost** — osmdroid `MapView` cannot survive `onDetachedFromWindow` regardless of `setDestroyMode`; tile-provider threads die permanently. Bug history: this was a real production crash.

## Article V — `MapView.zoomToBoundingBox()` is FORBIDDEN

The system shall NEVER call `MapView.zoomToBoundingBox()` — `Projection.getCloserPixel` blocks main thread >5s → ANR. Use direct `controller.setCenter()` + `controller.setZoom()` with manual bbox math. See MESHSAT-479.

## Article VI — JVM-test-safe imports

The system shall use `java.util.Base64` (NOT `android.util.Base64`) in any class reachable from JVM unit tests. The `BirthSigner.toCanonicalJson` helper substitutes for `org.json.JSONObject` (which returns null in JVM tests). `testOptions { unitTests.isReturnDefaultValues = true }` lets tests reference `android.util.Log` without NPE — keep enabled.

## Article VII — R8 keep-rules discipline

The system shall maintain `proguard-rules.pro` keep-rules for every reflection/JNI dep: ONNX Runtime, Room (`*_Impl`), BouncyCastle, AndroidX Security, NanoHTTPD, osmdroid (MESHSAT-493 — missing rules caused blank tiles), Tink `-dontwarn`. **Any new reflection-using dep MUST get a keep rule** or release builds crash where debug works.

## Article VIII — Foreground service hygiene

The system shall declare manifest `foregroundServiceType` AND defer service start until runtime permission grant. `GatewayService` uses `connectedDevice|location|remoteMessaging` types. `START_STICKY` survives process death; transport state reconciles on rebind via the 5-state `InterfaceManager` machine. Service start in `MainActivity` permission callback only.

## Article IX — Offline-first

The system shall function fully offline. Every transport, crypto, compression, map (post-cache), config, and telemetry must work air-gapped. Hub uplink is the only inherently-online component and it MUST fail closed (no crash) when offline.

## Article X — Local API surface is part of the contract

The system shall preserve the 14+ REST endpoints on `localhost:6051` (NanoHTTPD `LocalApiServer.kt`). Hub-controlled automation + E2E tests depend on them. Changes are breaking.

## Article XI — Reticulum wire format preservation

The system shall preserve wire compatibility with stock RNS (validated MESHSAT-208/267-272) for anything touching `reticulum/`. Wire-format changes require explicit ADR + interop test against stock RNS.

## Article XII — No external telemetry

The system shall NOT integrate Firebase, Crashlytics, Sentry, FCM, or any external telemetry service. Crash capture uses `UncaughtExceptionHandler` writing `filesDir/pending_crash.json` synchronously. Telemetry is local-only ring buffer (`telemetry` Room table, MESHSAT-494) with opt-out via `telemetryEnabled` DataStore pref. Operator-controlled REST pickup on `/api/telemetry`.

## Article XIII — Single Room migration per version

The system shall keep Room migrations single-author serialised. Current schema v14. NEVER parallelize tasks that touch `data/AppDatabase.kt` migrations — collisions corrupt the schema-version chain.

## Article XIV — Compose state correctness

The system shall handle configuration changes via Compose-idiomatic patterns (`rememberSaveable`, state-holder ViewModels). No XML/Fragment retain patterns. Dark theme is primary (Material 3); light is implicit.

## Article XV — Parallel-dev gates + workflow exception

The system shall enforce that no two parallelizable tasks share `files_owned` entries within the same dependency wave — validated at Phase F gate BEFORE any worker launches. The default workflow ("push directly to main, no branches, no MRs" — pipeline builds APK automatically) is overridden for parallel-dev waves: ONE short-lived `merge/<feature_id>` branch, ONE MR per feature, auto-delete on merge. See ADR-0003.

## Article XVI — Adapter parity with Bridge (same conceptual interface, language-idiomatic impl)

The system shall maintain conceptual-interface parity with Bridge per `../meshsat/docs/ARCHITECTURE.md` §"Shared Design Patterns". The 15-row parity matrix (channel registry, access rules, dispatcher, failover, dedup, rate limiting, transform pipeline, delivery ledger, interface state machine, Ed25519 identity, dead man's switch, geofence, health scores, burst queue, compression) is the contract. There is **NO shared compiled-code symbol surface** — implementations are language-idiomatic per runtime (Go on Bridge, Kotlin on Android). New patterns added to either platform MUST be added to the other within one minor release; the `README.md` Android-vs-Bridge parity matrix tracks the state. Deliberate asymmetries (e.g. HeMB bridge-only today, TAK receive-only on Android, no ZigBee on Android) MUST be documented in the matrix. See ADR-0004.

## Article XVII — Reticulum Transport Node with 10+ interfaces (NOT a client)

The system shall implement **full Reticulum Transport Node** behavior, NOT routing-client behavior: cross-interface relay, announce propagation with hop counting + dedup, path discovery, AES-256-GCM encrypted links with forward secrecy per session, cost-aware forwarding (paid bearers default `floodable=false`). Registered interfaces: Meshtastic BLE, Iridium SBD, Iridium IMT, SMS, MQTT, TCP/HDLC (stock-RNS interop), BLE peripheral (GATT server), Tor (SOCKS5), WireGuard, mesh, APRS. Wire-format compatibility with stock Python `rns` MUST stay green (validated MESHSAT-208/267-272). `RnsTransportNode.kt` is the single integration point with `GatewayService` — bringing up a new interface goes through that, NOT direct-wiring. See ADR-0005 + Article XI (RNS wire-format preservation).

## Article XVIII — MSVQ-SC compression asymmetric runtime (ONNX TX, pure-Kotlin RX)

The system shall implement MSVQ-SC with **asymmetric runtime requirements**: TX path uses ONNX Runtime Android (INT8 quantized `encoder.onnx`, 22 MB) via `MsvqscEncoder`; RX path uses pure-Kotlin codebook decoder via `MsvqscCodebook` with `codebook_v1.bin` (12 MB) + `corpus_index.bin` (70 KB) — **NO ML runtime dependency**. `MsvqscCodebook` MUST NOT take `ai.onnxruntime` as a compile-time dependency. ONNX cold-start ~500 ms on first compression call (lazy session init); subsequent calls fast. Wire-format additions to `MsvqscWire` are BREAKING — bump the header discriminator and ship both old + new decoders for one minor release. ProGuard / R8 keep rule `-keep class ai.onnxruntime.** { *; }` MUST stay in `proguard-rules.pro` (Article VII). See ADR-0006.

## Article XIX — GUI parity with Bridge SPA per `GUI-PARITY-SPEC.md`

The system shall implement Android UI screens against `GUI-PARITY-SPEC.md` (Playwright-captured 2026-03-16). The Bridge SPA is the source-of-truth for **operator concepts** (what filters exist, what the activity log shows, what cards live on the dashboard); Android may deviate on **rendering** (Compose Canvas instead of SVG; Material 3 FilterChip instead of Vue toggle) but NOT on **semantics**. New screens on either Bridge or Android MUST be added to `GUI-PARITY-SPEC.md` within the same release. When `meshsat/UX-AUDIT-AND-REDESIGN.md` Phase 3 (UI reshape) lands, the parity spec MUST be recaptured via Playwright; new Phase issues open against the diff. The hybrid Compose + WebView pair shell (ADR-0007) reduces this Article's scope to native-rendered hot paths — Settings / Interfaces / Rules / Audit go via WebView post-Phase-8. See ADR-0010.
