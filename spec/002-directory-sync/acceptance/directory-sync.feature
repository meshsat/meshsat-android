Feature: Android Directory Sync (meshsat-android Phase 5)

  Background:
    Given the Android app is paired with bridge B per spec/001-tofu-bundle-v2
    And the bridge_trust Room table has B's signing pubkey pinned
    And Room migration v14→v15 has applied the paired_bridge_* tables

  # REQ-102 + REQ-104 — happy-path snapshot pull
  Scenario: Snapshot pull applies signed payload to Room
    When DirectorySyncService runs against bridge B with since=0
    Then the request GET /api/directory/snapshot?since=0 is sent with Authorization Bearer
    And the response signed JSON is verified against B's pinned pubkey
    And paired_bridge_contacts contains the contacts from the snapshot

  # REQ-103 + REQ-105 — invalid signature rejected
  Scenario: Snapshot with bad signature is rejected
    When the bridge returns a snapshot whose Ed25519 signature does NOT verify
    Then the snapshot is NOT applied to Room
    And a log entry at WARN level names bridge B

  # REQ-106 — MQTT-driven sync
  Scenario: directory/changed MQTT triggers immediate pull
    Given Android is subscribed to meshsat/bridge/B/directory/changed
    When the bridge publishes a new version=N message
    Then within 5 seconds DirectorySyncService runs against B with since=current_version

  # REQ-107 — stale banner
  Scenario: PeopleScreen shows stale banner after 24 hours
    Given the last successful sync for B was 25 hours ago
    When the operator opens PeopleScreen
    Then a banner reads "Directory last synced 25 hours ago"

  # REQ-108..111 — PeopleScreen
  Scenario: PeopleScreen renders contacts with search + filter + detail navigation
    Given 50 contacts exist in paired_bridge_contacts for B
    When the operator opens PeopleScreen
    Then 50 rows are visible
    When the operator types "alice" in the search bar
    Then only rows whose display_name matches "alice" remain
    When the operator picks the "ops-team" filter chip
    Then only contacts whose team="ops-team" remain
    When the operator taps a contact row
    Then ContactDetailScreen opens showing addresses + groups + dispatch policy + trust dots

  # REQ-112 — Compose from contact detail
  Scenario: Compose action pre-populates recipient
    Given the operator is on alice's ContactDetailScreen
    When the operator taps "Compose"
    Then ComposeScreen opens with recipient=alice's contact_id

  # REQ-113 — QR export
  Scenario: Share via QR generates a CBOR card
    Given the operator is on alice's ContactDetailScreen
    When the operator taps "Share via QR"
    Then POST /api/directory/contacts/alice/qr/handoff is sent
    And the returned CBOR card is displayed as a QR code

  # REQ-114..116 — QR import
  Scenario: Scan contact QR imports + triggers sync
    When the operator taps "Scan contact QR" in PeopleScreen
    And scans a valid meshsat://contact/... QR
    Then POST /api/directory/contacts/import-handoff is sent to the active paired bridge
    And on success a directory sync is triggered
    And within 5 seconds the imported contact appears in PeopleScreen

  # REQ-117 — invalid handoff card
  Scenario: Expired card shows inline error
    When the operator scans a contact QR whose TTL has expired
    Then an inline error reads "Card expired"
    And NO retry is performed
