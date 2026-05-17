# Steering — Security baseline

## Layer 1 — Android Keystore for private keys

Per Article C-V, every client private key (X25519, Ed25519, signing keys for outgoing messages) goes into the Android Keystore via `KeyPairGenerator.getInstance("EC", "AndroidKeyStore")`. Raw key bytes never leave the secure storage area. Wrapper: `crypto/SecureKeyStore.kt`.

## Layer 2 — `EncryptedSharedPreferences` for JWTs + bearer tokens

Per Article C-VI, bearer JWTs from the pair-claim flow (future, spec/003) go in `EncryptedSharedPreferences` (AndroidX Security). Keys for the encrypted-prefs file are themselves Keystore-managed. Never plain `SharedPreferences` for credentials.

## Layer 3 — mTLS to paired bridges

Per spec/003 (future) and ADR-0008: every HTTP call to a paired bridge uses OkHttp with:

1. `CertificatePinner` pinning the bridge's SPKI captured during pair-claim.
2. `Interceptor` adding `Authorization: Bearer <jwt>` from EncryptedSharedPreferences.
3. SPKI mismatch → reject load with explicit error; do NOT fall back to system trust.

## Layer 4 — TOFU + Bundle v2 signature verification

Per ADR-0005 + spec/001 (retrospective): every key-bundle import verifies the v2 Ed25519 signature via `Signature.getInstance("Ed25519", "BC")` BEFORE applying any channel-key updates. Mismatched-pubkey path requires explicit operator accept (`acceptRepinForBridgeHash`).

## Layer 5 — Audit log

`data/AuditLogEntity.kt` + `data/AuditLogDao.kt` ship an append-only audit log of security-relevant events. Every key import, every pair claim (future), every profile switch records here. Operator can view via the Audit screen (`ui/screens/AuditScreen.kt`).

## Layer 6 — Backup exclusion

`AndroidManifest.xml` sets `android:allowBackup="false"` OR uses `android:fullBackupContent` rules that EXCLUDE:

- Android Keystore aliases (already excluded by platform).
- `EncryptedSharedPreferences` file.
- Room database file (`/data/data/.../databases/cubeos-meshsat.db`).
- `/data/data/.../files/key-bundle-cache/`.

Reason: backup-to-Google-account would exfiltrate credentials + bridge identities even if Keystore itself is safe.

## Layer 7 — Network security config

`res/xml/network_security_config.xml`:

- `cleartextTrafficPermitted="false"` for production.
- Domain-pinned cert chains for `*.cubeos.cube` (when configured) + bridge SPKI pins.
- Debug build allows cleartext to `127.0.0.1` (for LocalApiServer testing only).

## Forbidden patterns

- `Log.d/i/w/e` with raw secret values — log the redacted form `[REDACTED:type=jwt,len=N]` instead.
- `Gson.toJson` / `Moshi.toJson` on objects containing key material — explicit per-field projection only.
- `.commit()` synchronous SharedPreferences (use `.apply()` async) — except in security-critical paths where the write MUST be durable before continuing.
- HTTP cleartext in release builds — fails network_security_config validation.
