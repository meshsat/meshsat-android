# Steering — Security baseline (MeshSat Android)

## Secrets

- NEVER commit signing keys, OpenBao tokens, or Play Store credentials.
- Signing config (`signingConfigs.release`) reads from env vars in CI; debug uses debug.keystore.
- mTLS certs for Hub: stored in EncryptedSharedPreferences (Keystore-backed) via `SecureKeyStore.kt`.
- Hub access tokens: DataStore Preferences (encrypted by Android Keystore on API 23+ via `EncryptedSharedPreferences`).
- Tor / WireGuard config: encrypted at rest, decrypted only when transport is being initialised.

## Crypto

- BouncyCastle for Ed25519/X25519 (Conscrypt on Android 16 only returns AndroidKeyStore-bound generators — incompatible with raw 32-byte key export needed by Reticulum).
- AES-256-GCM for E2E encryption (`internal/crypto/MsvqscEncoder.kt` + per-device keystore).
- TOFU pinning of bridge identity (`bridge_trust` Room table). First-seen pubkey trusted; subsequent diffs rejected.
- Pair protocol (Phase 8 incoming): ECDH + HMAC + CSR + TLS SPKI pinning + `paired_bridges` Room table.

## Network

- Hub uplink: MQTT over TLS via Eclipse Paho with mTLS client cert verification.
- SPKI cert pinning fallback (`CertificatePinner.kt`) if mTLS not configured.
- HTTPS for any non-MQTT outbound (always TLS 1.2+; prefer 1.3).
- Local API surface (`localhost:6051`) is loopback-only — never bind to `0.0.0.0`.

## Permissions

- Declared: `RECEIVE_SMS`, `SEND_SMS`, `BLUETOOTH_*`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE` + 3 typed subvariants, `POST_NOTIFICATIONS`, `INTERNET`.
- SMS receiver `SmsReceiver.kt`: `android.permission.BROADCAST_SMS` priority 999.
- Runtime requests in `MainActivity` permission callback — service start deferred until granted (Article VIII).

## Dependency posture

- Every new dep: license check (GPL-incompatible deps blocked), CVE scan via `./gradlew dependencyCheckAnalyze`, transitive count review.
- Pin versions in `gradle/libs.versions.toml`. No version-range syntax.
- Periodic `./gradlew dependencies --refresh-dependencies` review.

## R8 hardening

- Release builds minified + obfuscated.
- `proguard-rules.pro` keep rules audited per Article VII.
- Test release builds via `ssh ansible@nllei01androidsdk01 './gradlew assembleRelease'` before tagging — debug-only testing misses R8 regressions.

## Telemetry (Article XII)

- NO Firebase, NO Crashlytics, NO Sentry, NO FCM.
- Local-only ring buffer in `telemetry` Room table.
- `UncaughtExceptionHandler` writes `pending_crash.json` synchronously.
- 6 REST endpoints under `/api/telemetry` on `localhost:6051` for operator-controlled pickup.
- Opt-out via `telemetryEnabled` DataStore pref.

## Audit (light)

- Security-relevant events logged to local file (not external service).
- Hub uplink events visible in Hub's audit log (Article VIII of Hub constitution).
- Bridge pair events logged to `paired_bridges` Room table with timestamp + cert fingerprint.
