# Requirements — TOFU + Bundle v2 (spec/001 — RETROSPECTIVE)

Source: ADR-0005 + `crypto/KeyBundleImporter.kt` (CGC-verified 2026-05-18) + `data/AppDatabase.kt` MIGRATION_12_13.

> Retrospective. Functionality shipped versionCode 50 / versionName 2.8.5; current 51 / 2.8.6.
> ID convention: 001-block (`001..099`).

## URI parsing

REQ-001: When the operator scans a QR code, the system shall parse URIs of the form `cubeos-meshsat://import/<base64-payload>` and route to KeyBundleImporter.parse.
REQ-002: When the URI is malformed, the system shall return `Malformed(reason)` and not apply any state change.

## v2 signature verification

REQ-003: The system shall verify the Ed25519 signature embedded in v2 bundles using `Signature.getInstance("Ed25519", "BC")`.
REQ-004: The system shall compute the signing payload as bytes 0..53 concatenated with bytes 118..end (excluding the bridge-hash + signature fields).
REQ-005: If the v2 signature is invalid, then the system shall return `InvalidSignature` and not apply the bundle.

## TOFU pinning

REQ-006: When a v2 bundle imports successfully and no `bridge_trust` row exists for the bridge-hash, the system shall insert a new row with pubkey + first_seen_at + last_seen_at and return `NEW_TRUSTED`.
REQ-007: When a v2 bundle imports successfully and a `bridge_trust` row exists with the same pubkey, the system shall update last_seen_at and return `EXISTING_TRUSTED`.
REQ-008: If a v2 bundle imports successfully but the pubkey differs from the stored row for the bridge-hash, then the system shall NOT apply the bundle and return `KeyMismatch(stored_pubkey, incoming_pubkey)`.
REQ-009: When the operator explicitly calls `acceptRepinForBridgeHash(hash)` after a KeyMismatch, the system shall update the stored pubkey and apply the bundle, returning `REPINNED_AFTER_MISMATCH`.

## v1 backward compat

REQ-010: When the URI decodes as a v1 (legacy unsigned) bundle, the system shall apply the channel keys and return `UNVERIFIED_V1` (operator-flag).

## ImportResult sealed class

REQ-011: The system shall expose `sealed class ImportResult` with exactly the variants `NEW_TRUSTED`, `EXISTING_TRUSTED`, `KeyMismatch`, `InvalidSignature`, `Malformed`, `UNVERIFIED_V1`, `REPINNED_AFTER_MISMATCH` defined INLINE inside `crypto/KeyBundleImporter.kt`.

## Schema delta

REQ-012: The system shall ship Room migration `MIGRATION_12_13` adding the `bridge_trust` table with columns: bridge_hash TEXT PRIMARY KEY, pubkey BLOB NOT NULL, first_seen_at LONG NOT NULL, last_seen_at LONG NOT NULL, alias TEXT.
REQ-013: The system shall append-only the migration to the existing migration list per Article C-IV (currently MIGRATION_5_6 through MIGRATION_13_14 shipped).

## Crypto provider posture

REQ-014: The system shall add BouncyCastle via `Security.addProvider(BouncyCastleProvider())` and pass the explicit `"BC"` provider name to `Signature.getInstance` per Article C-II.
REQ-015: The system shall NEVER invoke `Security.insertProviderAt(BC, N)` for any N.

## Per-channel AES-256-GCM key application

REQ-016: When `apply` succeeds, the system shall write each per-channel AES-256-GCM key to the conversation-key store via `data/ConversationKeyRepository.kt`.
REQ-017: While applying channel keys, the system shall NOT log raw key bytes (Article C-VI / steering/security-baseline.md Layer 7).
