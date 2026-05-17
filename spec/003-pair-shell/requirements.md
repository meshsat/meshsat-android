# Requirements — Android pair shell (spec/003)

Source: `meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md` §5 + `meshsat/EXECUTION-PLAN.md` §6.8 (Pair protocol v1) + ADRs 0006 + 0008 in this repo + `meshsat/spec/001-pair-protocol/`.

> Future-work. Phase 8 cross-cut Android stories S8-07 through S8-12.
> ID convention: 200-block (`200..299`).

## Three-way splash + mode selection

REQ-200: When the app launches for the first time, the system shall present a splash screen with 3 tiles: "Standalone", "Pair with Bridge", "Both (advanced)".
REQ-201: When the operator selects "Standalone", the system shall persist `app.mode = "standalone"` and route directly to the home screen.
REQ-202: When the operator selects "Pair with Bridge", the system shall route to the pair-scanner screen.
REQ-203: When the operator selects "Both (advanced)", the system shall start the standalone mode AND route to the pair-scanner screen.
REQ-204: The system shall expose Settings → Mode that re-opens the splash for re-entry.

## Pair-scanner + pair-claim

REQ-205: The system shall add `ui/screens/PairScannerScreen.kt` opening a CameraX camera + ZXing decoder targeting `meshsat://pair/<base64>` QRs displayed on a bridge's touch display.
REQ-206: When a pair QR is scanned, the system shall verify the embedded Ed25519 signature against the bridge's signing pubkey carried in the QR per `meshsat/spec/001-pair-protocol/`.
REQ-207: When signature verification succeeds, the system shall generate an X25519 client ephemeral keypair via Android Keystore (per Article C-V).
REQ-208: When the X25519 keypair is generated, the system shall generate an Ed25519 client signing keypair via Android Keystore + a PKCS#10 CSR, compute the X25519 shared-secret via the bridge's pubkey, and derive a session key via HKDF-SHA256.
REQ-209: The system shall open a TLS 1.3 connection to the bridge's lan.host via OkHttp configured with `CertificatePinner` pinning the SPKI value from the QR's `fp` field.
REQ-210: If the TLS handshake SPKI does NOT match the QR's `fp`, then the system shall abort the pair-claim before any payload is sent and display an error "bridge identity mismatch".
REQ-211: When the TLS handshake matches, the system shall POST `/api/v2/pair/claim` with `{csr, client_x25519_pub, hmac}` where the HMAC-SHA256 covers the body using the derived session key.
REQ-212: When the pair-claim returns HTTP 200, the system shall persist the returned client certificate + bearer JWT + bridge metadata (rns_announce, hub_url) into Android Keystore + EncryptedSharedPreferences (per Articles C-V + C-VI).

## Schema delta (Room v15→v16)

REQ-213: The system shall add a Room migration `MIGRATION_15_16` introducing a `paired_bridges` entity with columns: bridge_id PRIMARY KEY, host TEXT, cert_sha256 TEXT, jwt_alias TEXT, rns_announce TEXT, hub_url TEXT, paired_at INTEGER.
REQ-214: The system shall append `MIGRATION_15_16` to the existing migration list per Article C-IV.
REQ-215: The system shall expose a `PairedBridgesDao` interface with abstract methods `getAll`, `getById`, `insert`, `remove` per Article C-VII (JVM-testable).

## Hybrid Compose + WebView shell

REQ-216: The system shall add `ui/HybridShell.kt` (top-level orchestrator alongside `ui/MeshSatUI.kt`) that routes operator navigation between native Compose paths and WebView paths.
REQ-217: When the hybrid shell loads a WebView path (e.g. Settings/Interfaces), the system shall inject `Authorization: Bearer <jwt>` via OkHttp interceptor.
REQ-218: While the WebView loads any URL on a paired bridge, the system shall verify the response TLS SPKI against the stored cert_sha256 for that bridge and reject load on mismatch.

## Source switcher

REQ-219: The system shall add `ui/SourceSwitcher.kt` (top-level UI alongside `ui/MeshSatUI.kt` + `ui/HybridShell.kt`) rendering a Material 3 top-drawer with one entry per paired bridge plus a "This phone" entry when standalone mode is active.
REQ-220: When the operator selects a new source via the switcher, the system shall reload data-bound screens within 2 seconds.
REQ-221: The system shall add `ui/SourceStore.kt` as a Kotlin shared store (Compose-state-friendly) for current-source selection.

## Per-source notifications + nav-back

REQ-222: The system shall add `service/PerSourceNotifications.kt` (alongside existing `GatewayService.kt`) tagging each system notification with the source bridge_id.
REQ-223: When the operator taps a notification, the system shall switch the Source Switcher to the source bridge_id from the notification and open the relevant screen.

## Private key + remove-pairing flow

REQ-224: The system shall NEVER persist the raw client private key bytes outside Android Keystore (operator inspection of SharedPreferences/EncryptedSharedPreferences must show no key bytes).
REQ-225: The system shall add `data/RemovePairedBridge.kt` providing a single-call flow that deletes: the Keystore alias for the bridge, the `paired_bridges` row, all `paired_bridge_*` rows for that bridge_hash (cascade via FK from spec/002).

## Operator-mode persistence

REQ-226: The system shall add `config/AppMode.kt` (alongside existing `config/ConfigManager.kt`) persisting `app.mode` ∈ `{standalone, paired, both}` via the existing ConfigManager DataStore.
