# Design — TOFU + Bundle v2 Key Pinning

## Goal

Defend the QR-key-bundle import path against substitution attacks. A counterfeit QR with an attacker-controlled signing pubkey shall NOT be able to impersonate a previously-paired bridge. Modelled on Syncthing Device ID pinning, Signal "safety number changed", Matrix MSC4108. See `adr/0008-tofu-bundle-v2-key-pinning.md` for the decision rationale; this design captures the implementation contract.

## State diagram

```
                          ┌─────────────────────────┐
   meshsat://key/<bytes>  │                         │
   ─────────────────────► │   KeyBundleImporter     │
                          │   .import(uri, ...)     │
                          └────────────┬────────────┘
                                       │
                       ┌───────────────┼───────────────────────┐
                       │               │                       │
                       v               v                       v
              [version=0x01]   [version=0x02]             [malformed]
                       │               │                       │
              UNVERIFIED_V1   verify Ed25519 sig         Malformed
              + apply keys     (REQ-002)                 (REQ-007)
              (REQ-008)              │
                              ┌──────┴──────┐
                              │             │
                          [sig OK]      [sig BAD]
                              │             │
                              v             v
                       lookup            InvalidSignature
                       bridge_trust      (REQ-006)
                       row by bridge_hash
                              │
                       ┌──────┴──────┐
                       │             │
                  [not found]    [found]
                       │             │
                       v             v
                  INSERT row     compare embedded pubkey
                  + apply keys   vs stored
                  NEW_TRUSTED        │
                  (REQ-003)    ┌─────┴──────┐
                               │            │
                          [match]      [mismatch]
                               │            │
                               v            v
                       update last_seen_at  KeyMismatch
                       + apply keys         + REJECT bundle
                       EXISTING_TRUSTED     + show user warning
                       (REQ-004 + REQ-015)  (REQ-005)
                                                  │
                                                  v
                                          user accepts repin
                                          → caller invokes
                                          import(..., acceptRepin=h)
                                          → UPDATE pubkey + apply
                                          (REQ-009)
```

## Wire format (REQ-016)

```
offset  size  field
0       1     Version byte (0x01 = legacy, 0x02 = TOFU-pinned)
1       16    BridgeHash (16 bytes, identifies the bridge instance)
17      4     Timestamp (uint32 big-endian, unix epoch seconds when bundle was minted)
21      1     EntryCount (number of per-channel key entries that follow)
22      32    SigningPubkey (32 bytes, Ed25519 public key)
54      64    Signature (64 bytes, Ed25519 signature over bytes 0..53 + bytes 118..end)
118     ...   Entries (per-channel AES key blobs)
```

Signature input: ALL bytes except the 64 signature bytes themselves. The pubkey lives INSIDE the signed envelope, so an attacker cannot swap it without breaking the signature.

## Room schema delta (REQ-010, migration 12→13)

```sql
CREATE TABLE bridge_trust (
  bridge_hash       TEXT PRIMARY KEY,
  signing_pubkey    BLOB NOT NULL,
  first_seen_at     INTEGER NOT NULL,
  last_seen_at      INTEGER NOT NULL,
  status            TEXT NOT NULL
);
```

`status` values: `NEW_TRUSTED`, `EXISTING_TRUSTED`, `REPINNED_AFTER_MISMATCH`. The first two are normal pin states; the third records that a previously-mismatched bridge was deliberately re-pinned by the user.

## `ImportResult` sealed class (REQ-011)

```kotlin
sealed class ImportResult {
  data class Success(val bridgeHash: String, val status: TrustStatus) : ImportResult()
  data class KeyMismatch(val bridgeHash: String, val storedPubkey: ByteArray, val incomingPubkey: ByteArray) : ImportResult()
  object InvalidSignature : ImportResult()
  data class Malformed(val reason: String) : ImportResult()
}
```

Exhaustive `when` in callers; future variants do NOT silently fall through.

## JVM-test compatibility (REQ-013 + REQ-017)

- Uses `java.util.Base64` decoder. The `android.util.Base64` class returns null in JVM unit tests because the Android stub uses default-return-values stubbing per `testOptions { unitTests.isReturnDefaultValues = true }`.
- Accepts DAO interfaces (`BridgeTrustDao`, `ConversationKeyDao`) directly as constructor parameters. No `Context.applicationContext` dependency anywhere on the import path.

## Out of scope

- Cross-bridge identity binding (preventing an attacker from substituting bridge A's identity for bridge B). Future improvement; tracked separately.
- Hardware-attestation root of trust (manufacturer-attested cert per bridge). Not applicable to a self-hosted ecosystem.
- Automatic re-pin after legitimate key rotation. Manual re-pin via user-accept flow only.

## Cross-repo dependency

- The Bridge MUST emit Bundle v2 format. Tracked as MESHSAT-500 (cross-repo, open against `meshsat`). Until that lands, fielded bridges continue emitting v1 and operators see `UNVERIFIED_V1` (REQ-008).
