# Design — Android pair shell (spec/003)

Future-work. Closes Phase 8 cross-cut Android stories S8-07..S8-12 (per `meshsat/EXECUTION-PLAN.md` §6.8).

## Wire diagram

```
first-run splash (ui/screens/WelcomeSplashScreen.kt)
       │
   ┌───┼───┐
   ▼   ▼   ▼
Standalone  Pair  Both
   │        │     │
   │        ▼     ▼
   │   PairScannerScreen
   │   (ui/screens/PairScannerScreen.kt)
   │        │ CameraX + ZXing
   │        ▼
   │   verify QR.sig (Ed25519, "BC" provider)
   │        │
   │        ▼
   │   gen X25519 + Ed25519 keypairs in Keystore
   │   compute shared-secret + HKDF-SHA256
   │   open TLS 1.3 to lan.host
   │        │
   │        ▼
   │   pin SPKI == QR.fp
   │   if mismatch → abort + show error
   │        │
   │        ▼
   │   POST /api/v2/pair/claim {csr, client_x25519_pub, hmac}
   │        │
   │        ▼
   │   on 200: store cert+jwt+metadata
   │   insert paired_bridges row (Room v16)
   │        │
   ▼        ▼
home (HybridShell + SourceSwitcher)
```

## Top-level UI orchestrators (under ui/, alongside existing ui/MeshSatUI.kt)

| File | Purpose |
|---|---|
| `ui/MeshSatUI.kt` | existing — current top-level composable |
| `ui/HybridShell.kt` | NEW — orchestrates native Compose vs WebView routing per path |
| `ui/SourceSwitcher.kt` | NEW — Material 3 top-drawer |
| `ui/SourceStore.kt` | NEW — Kotlin shared store (Compose-state) for current-source |
| `ui/web/PinnedWebViewClient.kt` | NEW — WebView client enforcing SPKI pin + bearer header |

## Hybrid path table

| Path | Implementation |
|---|---|
| Paired-bridges list | native Compose (`ui/screens/PairedBridgesScreen.kt`) |
| Compose message | native Compose (`ui/screens/MessagesScreen.kt` — existing) |
| Inbox | native Compose (existing) |
| Map | native Compose (`ui/screens/MapScreen.kt` — existing, uses osmdroid) |
| People | native Compose (`ui/screens/PeopleScreen.kt` — spec/002) |
| Pair Devices | native Compose (`ui/screens/PairScannerScreen.kt` — new) |
| Settings → all other tabs | WebView via PinnedWebViewClient |
| Interfaces / Rules / Audit / Spectrum | WebView |
| Engineer-mode views | WebView |

## Why hybrid (recap from ADR-0008)

- Pure WebView: kills native gestures + native notifications.
- Pure Compose: forces reimplementing every Bridge Settings tab in Kotlin.
- Hybrid: native for hot paths; WebView for cold paths.

## Schema (Room v15→v16)

```sql
CREATE TABLE paired_bridges (
  bridge_id TEXT PRIMARY KEY NOT NULL,
  host TEXT NOT NULL,             -- LAN host or hostname
  cert_sha256 TEXT NOT NULL,      -- SPKI hex (TLS pin)
  jwt_alias TEXT NOT NULL,        -- EncryptedSharedPreferences key
  rns_announce TEXT,              -- Reticulum identity announcement (optional)
  hub_url TEXT,                   -- optional Hub WebSocket relay URL
  paired_at INTEGER NOT NULL
);
```

Foreign-key from `paired_bridges.bridge_id` is referenced by `paired_bridge_*` tables from spec/002.

## Pair-claim protocol (bridge contract)

Bridge exposes `POST /api/v2/pair/claim` (defined in `meshsat/spec/001-pair-protocol/`). Request body:

```json
{
  "csr": "<base64 PKCS#10>",
  "client_x25519_pub": "<base64>",
  "hmac": "<hex HMAC-SHA256 over (csr || client_x25519_pub) using HKDF-derived session key>"
}
```

Response on 200:

```json
{
  "client_cert": "<PEM>",
  "client_jwt": "<JWT>",
  "ca_chain": "<PEM>",
  "rns_announce": "<base64>",
  "hub_url": "wss://..."
}
```

## Out of scope

- Bridge-side pair-protocol implementation (owned by `meshsat/spec/001-pair-protocol/`).
- Multi-bridge backend on the Bridge side (owned by `meshsat/spec/009-multi-bridge-nat/`).
- Push-notification service registration — local foreground service only (existing `service/GatewayService.kt`).
