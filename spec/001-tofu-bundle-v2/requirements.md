# Requirements — TOFU + Bundle v2 Key Pinning (meshsat-android v2.8.5, MESHSAT-495)

This is a **retrospective Phase A spec** for the already-shipped feature in `app/build.gradle.kts` versionCode 50 (v2.8.5, released 2026-03). Source: `README.md` L183–L194 (Security & Encryption / TOFU Pinning), `CLAUDE.md` L90 (recent release history), `adr/0008-tofu-bundle-v2-key-pinning.md` (architectural decision). Validates that the shipped behavior matches the documented spec.

Constitution invariants in scope: Article III (BouncyCastle provider ordering — Ed25519 verification calls pass "BC" explicitly), Article VI (`java.util.Base64`, NOT `android.util.Base64`), Article XIII (single Room migration per version — migration 12→13 owns this), Article XIV (Compose state correctness for the user-facing accept-re-pin prompt).

## Functional requirements

REQ-001: The `KeyBundleImporter` shall accept QR URIs matching the pattern `meshsat://key/<base64url-payload>`.

REQ-002: When a bundle's version byte is `0x02`, the importer shall verify that the embedded Ed25519 signature covers all bytes of the payload except the 64 signature bytes themselves.

REQ-003: When a v2 bundle is imported for a `bridge_hash` not present in the `bridge_trust` Room table, the importer shall insert a row with the embedded signing pubkey pinned and shall return `ImportResult=Success` carrying status `NEW_TRUSTED`.

REQ-004: When a v2 bundle is imported for a `bridge_hash` already in `bridge_trust` and the embedded pubkey matches the stored pubkey, the importer shall return `ImportResult=Success` carrying status `EXISTING_TRUSTED`.

REQ-005: When a v2 bundle is imported for a `bridge_hash` already in `bridge_trust` and the embedded pubkey differs from the stored pubkey, the importer shall return `ImportResult=KeyMismatch` and shall NOT apply the bundle.

REQ-006: When the Ed25519 signature on a v2 bundle fails verification, the importer shall return `ImportResult=InvalidSignature` and shall NOT apply the bundle.

REQ-007: When a bundle is malformed (the payload fails base64url decoding OR has wrong byte length OR contains invalid field values), the importer shall return `ImportResult=Malformed`.

REQ-008: When a bundle's version byte is `0x01` (legacy v1 format), the importer shall apply the bundle and shall return `ImportResult=Success` carrying status `UNVERIFIED_V1`.

REQ-009: When the user accepts a `KeyMismatch` warning and the caller re-invokes import with `acceptRepinForBridgeHash` set to the matching bridge hash, the importer shall update the stored `bridge_trust` pubkey and apply the bundle.

REQ-010: The `bridge_trust` Room table shall have columns `bridge_hash TEXT PRIMARY KEY`, `signing_pubkey BLOB NOT NULL`, `first_seen_at INTEGER NOT NULL`, `last_seen_at INTEGER NOT NULL`, and `status TEXT NOT NULL`.

REQ-011: The `ImportResult` sealed Kotlin class shall expose exactly four variants — `Success`, `KeyMismatch`, `InvalidSignature`, and `Malformed`.

REQ-012: While Room migration 12-to-13 is running, the importer shall block new bundle imports.

REQ-013: The `KeyBundleImporter` shall use `java.util.Base64` and shall NOT use `android.util.Base64`, so the importer remains testable from JVM unit tests.

REQ-014: When a v2 bundle's `entries` array contains a per-channel AES key, the importer shall write each key into the appropriate channel-keys storage.

REQ-015: When the import succeeds with `EXISTING_TRUSTED`, the importer shall update the `bridge_trust.last_seen_at` timestamp.

REQ-016: The v2 bundle binary format shall be exactly the byte sequence `Version(1 byte) | BridgeHash(16 bytes) | Timestamp(4 bytes) | EntryCount(1 byte) | SigningPubkey(32 bytes) | Signature(64 bytes) | Entries`.

REQ-017: When the importer is invoked from a JVM test rather than an Android device, the importer shall accept DAO interfaces directly without requiring an Android `Context` instance.
