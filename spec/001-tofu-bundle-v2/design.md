# Design — TOFU + Bundle v2 (spec/001)

Retrospective spec. All file paths + line numbers CGC-verified 2026-05-18.

## Wire diagram

```
operator scans QR
       │
       ▼  cubeos-meshsat://import/<base64>
+------------------+
| KeyBundleImporter|   crypto/KeyBundleImporter.kt
| .parse(uri)      |
+--------+---------+
         │
         ├─ malformed → ImportResult.Malformed(reason)
         │
         ├─ v1 bundle ──► apply channel keys → ImportResult.UNVERIFIED_V1
         │
         ▼ v2 bundle
+----------------------+
| verify Ed25519 sig   |   uses Signature.getInstance("Ed25519", "BC")
| over bytes 0..53     |
| + bytes 118..end     |
+----+-----+-----+-----+
     │     │     │
     ▼     │     ▼
 invalid   │   valid
   sig ────┘   │
ImportResult   ▼
.InvalidSig    │
              ┌┴───────────────────┐
              ▼                    ▼
       look up bridge_trust   no row exists
       row for bridge_hash         │
       │                           ▼
       ├ stored pubkey ==      INSERT row → ImportResult.NEW_TRUSTED
       │  incoming pubkey
       │   ↓
       │  touch last_seen_at
       │  → ImportResult.EXISTING_TRUSTED
       │
       └ stored pubkey !=
          incoming pubkey
           ↓
         do NOT apply
         → ImportResult.KeyMismatch(stored, incoming)
            (operator can then call
             acceptRepinForBridgeHash(hash)
             → REPINNED_AFTER_MISMATCH)
```

## File-level layout (CGC-verified)

| File | Real path | Purpose |
|---|---|---|
| KeyBundleImporter | `app/src/main/java/com/cubeos/meshsat/crypto/KeyBundleImporter.kt` | Parse + verify + apply |
| ImportResult | INLINE at `crypto/KeyBundleImporter.kt:95` | sealed class of variants |
| BridgeTrustEntity | `app/src/main/java/com/cubeos/meshsat/data/BridgeTrustEntity.kt` | Room entity |
| BridgeTrustDao | `app/src/main/java/com/cubeos/meshsat/data/BridgeTrustDao.kt` | DAO interface |
| AppDatabase | `app/src/main/java/com/cubeos/meshsat/data/AppDatabase.kt` | Room DB (version=14; MIGRATION_12_13 adds bridge_trust) |
| KeyBundleImporterTest | `app/src/test/java/com/cubeos/meshsat/KeyBundleImporterTest.kt` | Tests at flat layout per Article C-VIII |

## Schema (MIGRATION_12_13)

```sql
CREATE TABLE IF NOT EXISTS bridge_trust (
  bridge_hash TEXT PRIMARY KEY NOT NULL,
  pubkey BLOB NOT NULL,
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  alias TEXT
);
```

Indexed by PRIMARY KEY on `bridge_hash`. No additional indexes (low row count expected).

## v2 wire format

| Offset | Length | Field |
|---|---|---|
| 0..3 | 4 | magic `MSV2` |
| 4..53 | 50 | bridge metadata header (hash, version, timestamp, channel count, ...) |
| 54..117 | 64 | Ed25519 signature (excluded from signed bytes) |
| 118..end | var | per-channel keys + bridge alias |

Signature covers bytes 0..53 + 118..end (the holes are the bridge-hash + signature fields).

## v1 wire format (legacy)

| Offset | Length | Field |
|---|---|---|
| 0..3 | 4 | magic `MSV1` |
| 4..end | var | per-channel keys (no signature) |

Imports as `UNVERIFIED_V1`. Operator-visible warning in UI.

## Out of scope

- Pair-claim flow (key bundle import is one-way — bridge → app — no key exchange) — owned by spec/003.
- Multi-bridge directory sync — owned by spec/002.
- HSM/StrongBox enforcement of stored pubkeys — not necessary for trust-on-first-use anchors (they're public material).
