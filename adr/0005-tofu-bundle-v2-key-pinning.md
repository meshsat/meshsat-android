# 5. TOFU + Bundle v2 key-bundle import

Date: 2026-05-18 (codifying shipped 2026-03 design)

## Status

Accepted (RETROSPECTIVE — feature shipped versionCode 50 / versionName 2.8.5; current 51 / 2.8.6)

## Context

Operators provision the Android app by scanning a QR code from the Bridge containing a "key bundle" — a binary payload with bridge identity (Ed25519 signing pubkey), per-channel AES-256-GCM keys, and metadata. We need:

1. Verify the bundle is signed (the Bridge has the signing pubkey embedded — first-time installs trust on first use).
2. Detect mismatched pubkey on subsequent imports (operator likely scanned wrong bridge OR bridge was rotated → either should NOT silently overwrite).
3. Support v1 (legacy, unsigned) and v2 (signed) wire formats.

## Decision

Implement `crypto/KeyBundleImporter.kt` as the canonical importer with:

- `parse(uri)` → decodes `cubeos-meshsat://import/<base64-v1-or-v2-payload>`
- v2: Ed25519 signature covers bytes 0..53 + 118..end (the holes are the bridge-hash + signature fields themselves)
- TOFU: first import for a bridge-hash → store the pubkey in `data/bridge_trust` table (added in `MIGRATION_12_13`) + return `NEW_TRUSTED`
- Matching pubkey on subsequent import → `EXISTING_TRUSTED` + touch `last_seen_at`
- Mismatched pubkey → `KeyMismatch` sealed-class variant; do NOT apply the bundle
- Operator can explicitly accept the new pubkey via `acceptRepinForBridgeHash(hash)` → `REPINNED_AFTER_MISMATCH`
- v1 → `UNVERIFIED_V1` (apply, but flagged for operator review)
- `ImportResult` sealed class with 4 variants lives INLINE at line 95 of `KeyBundleImporter.kt`

## Consequences

**Positive:**
- First-pin works without out-of-band trust setup.
- Mismatch is operator-visible (not silently overwritten).
- v1 bundles still import for backward compat with pre-Bundle-v2 bridges.

**Negative:**
- Operator can be socially engineered into accepting a mismatched pubkey (the `acceptRepinForBridgeHash` path). Mitigation: UI clearly shows what changed; audit log records the repin event.

**Cross-reference:** spec/001-tofu-bundle-v2 (retrospective).
