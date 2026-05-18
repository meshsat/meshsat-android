Feature: Android directory sync (spec/002)

  # Covers: REQ-100, REQ-101, REQ-102, REQ-103, REQ-104, REQ-105, REQ-106, REQ-107, REQ-108, REQ-109, REQ-110, REQ-111, REQ-112, REQ-113, REQ-114, REQ-115, REQ-116, REQ-117, REQ-118, REQ-119, REQ-120

  Background:
    Given the Android app is paired with bridge B (bridge_trust row exists)
    And Room is at version 15 with paired_bridge_* tables

  # REQ-104 + REQ-105 + REQ-107
  Scenario: First sync from bridge populates paired_bridge_* tables
    When DirectorySyncService runs the first poll for bridge B
    Then a GET /api/directory/snapshot?since=0 is issued
    And the response's Ed25519 signature is verified against bridge_trust.pubkey for B
    And on valid sig the contacts + addresses + groups + dispatch_policies are inserted in a single Room transaction

  # REQ-106 — failed sig
  Scenario: Signature mismatch logs WARN and does not apply
    Given a snapshot response with tampered bytes
    When DirectorySyncService verifies
    Then signature verification fails
    And AuditLog records a WARN event
    And no row is inserted

  # REQ-108 — MQTT trigger
  Scenario: MQTT directory/changed event triggers immediate pull
    Given DirectorySyncService is subscribed to meshsat/bridge/<B>/directory/changed
    When an MQTT event arrives
    Then a snapshot pull starts within 5 seconds

  # REQ-109 + REQ-110 — UI navigation
  Scenario: PeopleScreen lists contacts; tap navigates to ContactDetailScreen
    When the operator opens the People tab
    Then PeopleScreen shows the union of contacts from all paired bridges
    When the operator taps a contact
    Then ContactDetailScreen opens showing addresses + groups + dispatch policies + source bridge_hash

  # REQ-111 — stale banner
  Scenario: 25-hour-old data shows stale banner
    Given paired_bridge_contacts for bridge B was last synced 25 hours ago
    When the operator opens People
    Then a stale-data banner appears with the message "last synced 25 hours ago"

  # REQ-112 — QR export
  Scenario: Contact card QR is shareable
    Given a contact "Alice" exists
    When the operator taps Share → QR on Alice
    Then ContactQRDisplay renders a base64-encoded vCard QR

  # REQ-114 + REQ-115 — QR import + sig verify
  Scenario: Imported contact-card QR is signature-verified
    Given another operator shares Alice's signed contact-card QR
    When the receiving operator scans via ContactQRScanner
    Then ContactCardImporter verifies the Ed25519 signature with "BC" provider
    And on valid sig a paired_bridge_contacts row is added
    And on invalid sig an inline UI error is shown + no row added

  # REQ-117 — KeyBundleImporter unmodified
  Scenario: Adding ContactCardImporter does not modify KeyBundleImporter
    When code review compares before/after
    Then crypto/KeyBundleImporter.kt diff is empty
    And the inline ImportResult sealed class at line 95 is unchanged
