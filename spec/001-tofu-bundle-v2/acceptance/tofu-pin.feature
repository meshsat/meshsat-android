Feature: TOFU + Bundle v2 key-bundle import (spec/001 — RETROSPECTIVE)

  Background:
    Given the bridge_trust Room table exists at version 14
    And BouncyCastle is registered via Security.addProvider

  # REQ-006 — TOFU first-pin
  Scenario: First import of an unseen bridge pins the pubkey
    Given no bridge_trust row exists for hash "abc..."
    When the operator imports a v2 key bundle with bridge_hash="abc..."
    Then a bridge_trust row is inserted with pubkey from the bundle
    And the result is NEW_TRUSTED

  # REQ-007 — repeat-with-same-pubkey
  Scenario: Re-import with matching pubkey touches last_seen_at
    Given a bridge_trust row exists for hash "abc..." with pubkey P
    When the operator imports a v2 bundle for hash "abc..." with the same pubkey P
    Then the bridge_trust row's last_seen_at is updated
    And the result is EXISTING_TRUSTED

  # REQ-008 — mismatch is sticky
  Scenario: Mismatched pubkey is rejected; no state change
    Given a bridge_trust row exists for hash "abc..." with pubkey P_old
    When the operator imports a v2 bundle for hash "abc..." with pubkey P_new (P_new != P_old)
    Then the bridge_trust row is NOT modified
    And no channel keys are applied
    And the result is KeyMismatch(P_old, P_new)

  # REQ-009 — operator-approved repin
  Scenario: acceptRepinForBridgeHash overrides + applies
    Given a previous import returned KeyMismatch for hash "abc..."
    When the operator calls KeyBundleImporter.acceptRepinForBridgeHash("abc...")
    Then the stored pubkey is replaced with the incoming pubkey
    And the channel keys are applied
    And the result is REPINNED_AFTER_MISMATCH

  # REQ-005 — bad signature
  Scenario: Tampered v2 bundle rejected at signature verify
    Given a v2 bundle has a tampered byte at offset 200 (in the signed range)
    When the operator imports
    Then Signature.getInstance("Ed25519", "BC").verify returns false
    And no state change occurs
    And the result is InvalidSignature

  # REQ-002 — malformed URI
  Scenario: Malformed URI returns Malformed result
    When the operator imports "cubeos-meshsat://import/not-base64"
    Then the result is Malformed with a non-empty reason
    And no state change occurs

  # REQ-010 — v1 backward compat
  Scenario: v1 bundle imports as UNVERIFIED_V1
    Given a v1 (legacy unsigned) bundle
    When the operator imports
    Then the channel keys are applied
    And the result is UNVERIFIED_V1
    And the UI shows an operator-flag banner

  # REQ-011 — sealed class structure
  Scenario: ImportResult sealed class is inline
    When a developer inspects crypto/KeyBundleImporter.kt
    Then the sealed class ImportResult is present at line 95
    And no separate ImportResult.kt file exists
    And the variants include NEW_TRUSTED, EXISTING_TRUSTED, KeyMismatch, InvalidSignature, Malformed, UNVERIFIED_V1, REPINNED_AFTER_MISMATCH

  # REQ-014 — BC provider posture
  Scenario: BouncyCastle is added (not inserted at priority 1)
    When the app starts
    Then Security.addProvider(BouncyCastleProvider()) is called
    And Security.insertProviderAt(BC, N) is NEVER called for any N
    And Signature.getInstance call sites pass "BC" explicitly
