# Steering — Test strategy (MeshSat Android)

## Pyramid

- **Unit (JVM):** `app/src/test/` — ~45 test files / ~400 tests. JVM-runnable, fast, no emulator needed.
- **Integration:** none formal today (gap).
- **Instrumented (Espresso/UI Automator):** **GAP — `app/src/androidTest/` directory does NOT exist.** Parallel-dev workers cannot meaningfully use Espresso. Real-device validation only on the emulator at `nllei01androidsdk01`.
- **Live-device manual:** Pixel 9a emulator with bonded HC-05 + Meshtastic radio for transport-path validation.

## CI

All gradlew invocations via SSH (Article II):

```bash
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew testDebugUnitTest'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew lintDebug'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew check'  # tests + lint
```

For release verification:
```bash
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew assembleRelease'
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew bundleRelease'
```

`testOptions { unitTests.isReturnDefaultValues = true }` lets JVM tests reference `android.util.Log` without NPE. Keep enabled.

## What JVM unit tests CAN cover

- Pure logic in `engine/`, `rules/`, `crypto/codec/`, `routing/Identity.kt` (Ed25519/X25519 math), `channel/`, `dedup/`, `ratelimit/`, `engine/AccessEvaluator.kt`, message marshalling, codebook lookups, position codec.
- Room DAOs via `Room.inMemoryDatabaseBuilder` (Android-aware via Robolectric if needed).
- ViewModel state-flow logic (no Compose UI).

## What JVM unit tests CANNOT cover

- Compose UI rendering (no instrumentation; deferred to operator screenshot verification).
- BLE / Bluetooth SPP / SMS / actual radio I/O (hardware).
- osmdroid map rendering (requires View hierarchy).
- Hub MQTT connection (requires a real broker; mochi-mqtt or embedded Paho can be used in JVM but with caveats).
- ONNX Runtime inference (requires NDK; out-of-scope for unit tests).

## Coverage targets (aspirational)

- Pure logic packages: 80% lines.
- DAOs: 70% lines.
- UI screens: 0% via unit tests; relies on manual smoke + operator screenshot review.

## Parallel-dev workers

Workers must run `./gradlew testDebugUnitTest` (via the SSH command above) as `acceptance_test`. Per-package tests are insufficient for cross-package regressions.

## Test ergonomics

- Real-device validation: `adb` proxy through `nllei01androidsdk01`. Operator-mediated for non-emulator devices.
- Crash testing: deliberately throw in `UncaughtExceptionHandler` path, verify `pending_crash.json` written + recovered on next start.
- Telemetry pickup test: `curl localhost:6051/api/telemetry/recent` after seeded events.

## Honest gaps

- No instrumented UI tests (no `androidTest/` dir). Parallel-dev workers can't validate UI changes — operator manual review required.
- No coverage threshold enforcement in CI today. Aspiration in this doc, not policy.
- Real-device fragmentation (Pixel-only on emulator) means OEM-specific bugs (Samsung One UI quirks, Xiaomi battery optimization, etc.) caught only by user reports.
