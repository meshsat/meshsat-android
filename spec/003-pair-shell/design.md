# Design — Android Pair Shell (Phase 8)

## Goal

Make Android a first-class operator surface for one or many bridges per `meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md §5`. Three concurrent threads:

1. **Three-way splash + mode selection** — first-run picker drives whether the app is a standalone gateway, paired controller, or both.
2. **Pair-claim flow** — operator scans the bridge's touch-display QR, app does ECDH + CSR + JWT exchange, stores certs in hardware Keystore.
3. **Hybrid shell** — native Compose for hot paths, WebView for Settings — single SPA reuse for cold paths.

## Wire diagram

```
                first-run splash
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
   Standalone     Pair w/ Bridge     Both
       │              │               │
       │              ▼               ▼
       │       PairScannerScreen   start standalone
       │       │ (CameraX+ZXing)   + open pair flow
       │       ▼
       │   verify QR Ed25519 sig
       │   gen X25519 client_eph
       │   gen Ed25519 client_spk
       │   gen PKCS#10 CSR
       │   compute ss via X25519
       │   open TLS 1.3 to lan.host
       │   pin SPKI == QR.fp
       │   POST /api/v2/pair/claim
       │   ◀── 200 + {bearer,cert,ca,rns,hub}
       │   store cert+key in Keystore
       │   insert paired_bridges row
       │              │
       ▼              ▼
   home screen with Source switcher
        │              │
        ▼              ▼
   This phone     bridge B1, B2, ...
   (standalone    (each with their
    Room data)     own data scope)
```

## Hybrid shell mapping

| Path | Implementation |
|---|---|
| Paired-bridges list | Native Compose |
| Compose | Native Compose |
| Inbox | Native Compose |
| Map | Native Compose (osmdroid — Article IV: OUTSIDE NavHost) |
| People | Native Compose (uses paired_bridge_contacts from spec/002) |
| Pair Devices (Settings → Devices) | Native Compose |
| Settings everything else | WebView wrapping bridge's Vue SPA at /settings |
| Interfaces / Rules / Audit / Spectrum | WebView |
| Engineer mode views | WebView |

## Why hybrid (not pure WebView, not pure Compose)

- Pure WebView: kills native gestures, native notifications, native foreground service integration. Bad UX for hot paths.
- Pure Compose: forces re-implementing every Bridge Settings tab in Kotlin. Maintenance horror.
- Hybrid: native feel for hot paths + auto-update for cold paths. Same pattern as Home Assistant Companion app.

## Keystore + WebView injection

- Client private key: `KeyStore` "AndroidKeyStore" provider with EC-P256 generated via `KeyPairGenerator.getInstance("EC", "AndroidKeyStore")` + `setUserAuthenticationRequired(false)` (operator authenticated once at app start).
- Bearer JWT: `EncryptedSharedPreferences` (AndroidX Security) per-bridge.
- WebView injection: `WebViewClient.shouldInterceptRequest` → return modified `WebResourceResponse` with `Authorization: Bearer <jwt>` header. The interceptor also verifies the response's TLS SPKI against the pinned `cert_sha256` per REQ-217/218.

## Source switcher

Top-drawer Material 3 component. Renders:
- "This phone" entry (when app.mode=standalone or both)
- Each paired bridge entry (with health dot from spec/009 REQ-800)

Selecting an entry triggers a single store mutation that downstream views observe via Compose `state`. Hot paths reload within 2 seconds (REQ-220).

## Out of scope

- Bridge pair-protocol implementation (already shipped — `meshsat/spec/001-pair-protocol/`).
- Multi-bridge backend (bridge side: `meshsat/spec/009-multi-bridge-nat/`).
- Directory sync (`meshsat-android/spec/002-directory-sync/`).
- Push notification service registration — local foreground service only.
