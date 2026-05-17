Feature: TOFU + Bundle v2 Key Pinning (meshsat-android v2.8.5, MESHSAT-495)

  Parent epic: MESHSAT-495. Shipped in v2.8.5 (versionCode 50, 2026-03).

  Background:
    Given a freshly-installed MeshSat Android app
    And the bridge_trust Room table is empty
    And the importer is invoked with DAO interfaces directly (no Context required)

  # REQ-003 + REQ-008 — first-import behaviors
  Scenario: First import of a v2 bundle from an unknown bridge
    Given a v2 bundle minted by bridge_hash=abc123... with signing_pubkey=PK1
    When the importer processes the bundle's QR URI
    Then the result is Success with status NEW_TRUSTED
    And a bridge_trust row exists with bridge_hash=abc123... and signing_pubkey=PK1

  Scenario: First import of a v1 bundle returns UNVERIFIED_V1
    Given a v1 (legacy) bundle minted by bridge_hash=def456...
    When the importer processes the bundle's QR URI
    Then the result is Success with status UNVERIFIED_V1
    And the bundle's per-channel keys are applied to channel-keys storage

  # REQ-004 + REQ-015 — re-import of a known bridge with matching pubkey
  Scenario: Re-import of a v2 bundle from a known bridge with matching pubkey
    Given bridge_trust contains bridge_hash=abc123... with signing_pubkey=PK1 and last_seen_at=1000
    And a new v2 bundle minted by bridge_hash=abc123... with signing_pubkey=PK1
    When the importer processes the bundle's QR URI at time=2000
    Then the result is Success with status EXISTING_TRUSTED
    And the bridge_trust row's last_seen_at is updated to 2000

  # REQ-005 — pubkey mismatch is rejected
  Scenario: Re-import of a v2 bundle from a known bridge with DIFFERENT pubkey
    Given bridge_trust contains bridge_hash=abc123... with signing_pubkey=PK1
    And a new v2 bundle minted by bridge_hash=abc123... with signing_pubkey=PK2
    When the importer processes the bundle's QR URI
    Then the result is KeyMismatch carrying storedPubkey=PK1 and incomingPubkey=PK2
    And the bridge_trust row's signing_pubkey is still PK1
    And the bundle's per-channel keys are NOT applied

  # REQ-009 — user-accepted re-pin
  Scenario: User accepts a KeyMismatch re-pin
    Given bridge_trust contains bridge_hash=abc123... with signing_pubkey=PK1
    And a v2 bundle minted by bridge_hash=abc123... with signing_pubkey=PK2 was previously rejected with KeyMismatch
    When the importer is invoked with the same URI and acceptRepinForBridgeHash=abc123...
    Then the result is Success with status REPINNED_AFTER_MISMATCH
    And the bridge_trust row's signing_pubkey is updated to PK2
    And the bundle's per-channel keys are applied

  # REQ-006 — invalid signature is rejected
  Scenario: v2 bundle with an invalid Ed25519 signature
    Given a v2 bundle with a corrupted signature byte
    When the importer processes the bundle's QR URI
    Then the result is InvalidSignature
    And NO bridge_trust row is created or updated
    And the bundle's per-channel keys are NOT applied

  # REQ-007 — malformed payloads
  Scenario: Bundle with non-base64url characters in the URI
    Given a bundle URI containing the byte "!" (invalid base64url)
    When the importer processes the URI
    Then the result is Malformed with a reason describing the base64 failure

  Scenario: Bundle with payload length below the v2 header minimum
    Given a bundle whose decoded payload is 50 bytes (less than 118-byte header)
    When the importer processes the URI
    Then the result is Malformed with a reason describing the length violation

  # REQ-002 + REQ-016 — signature coverage matches the v2 wire format
  Scenario: Signature covers everything except the signature bytes
    Given a v2 bundle whose signature is verified BYTE-BY-BYTE against the published wire format
    When the importer reconstructs the signing input
    Then the signing input is the concatenation of bytes 0..53 (Version+BridgeHash+Timestamp+EntryCount+SigningPubkey) and bytes 118..end (Entries)
    And the signing input does NOT include bytes 54..117 (the Signature region)

  # REQ-011 — sealed class exhaustiveness
  Scenario: ImportResult sealed class exposes exactly four variants
    When the test reflects on the ImportResult sealed class
    Then exactly four variants are found: Success, KeyMismatch, InvalidSignature, Malformed

  # REQ-013 — JVM-test compatibility (java.util.Base64)
  Scenario: KeyBundleImporter is callable from a JVM unit test
    Given a JVM test with no android.* runtime
    When the importer is instantiated with stub DAO interfaces
    Then no NullPointerException or ClassNotFoundException is raised
    And the importer processes a known-good v2 bundle to Success+NEW_TRUSTED
