# Requirements — Android Directory Sync (meshsat-android Phase 5, EXECUTION-PLAN §6.5)

Source: `meshsat/EXECUTION-PLAN.md` §6.5 (3 stories S5-01..S5-03). Cross-cuts `meshsat/spec/006-android-directory-sync/` — that spec captures the bridge side; this spec captures the Android Room+Compose+QR-scan surface.

Constitution invariants in scope: Article III (BouncyCastle provider ordering with "BC" suffix), Article VI (java.util.Base64 + DAO interfaces directly), Article XI (Reticulum wire-format preservation), Article XIII (single Room migration per version), Article XIV (Compose state correctness via rememberSaveable).

When the operator pairs Android with a bridge per `spec/001-tofu-bundle-v2/`, this phase wires the contact directory to pull from the bridge over HTTPS, render in a native Compose People view, and support QR contact-card handoff in either direction.

## Functional requirements

REQ-100: The Android app shall add Room migration v14→v15 introducing tables `paired_bridge_contacts`, `paired_bridge_addresses`, `paired_bridge_groups`, `paired_bridge_dispatch_policies`, mirroring the bridge's v44-v48 schema from `meshsat/spec/002-unified-directory/`.

REQ-101: The Room migration v14→v15 shall be the only schema change in this release per Constitution Article XIII.

REQ-102: The Android app shall implement a `DirectorySyncService` that pulls `GET /api/directory/snapshot?since={last_version}` from each paired bridge.

REQ-103: When the snapshot returns 200, the system shall verify the Ed25519 signature against the bridge's pinned signing pubkey from the `bridge_trust` Room table per `spec/001-tofu-bundle-v2/`.

REQ-104: When the snapshot signature verifies, the system shall apply the snapshot to the `paired_bridge_*` Room tables in a single transaction.

REQ-105: When the snapshot signature does NOT verify, the system shall reject the snapshot AND shall write a log entry at WARN level naming the bridge.

REQ-106: When the bridge publishes a MQTT message on `meshsat/bridge/{bridge_id}/directory/changed`, the `DirectorySyncService` shall trigger an immediate snapshot pull.

REQ-107: When the snapshot is stale by more than 24 hours, the Android People view shall display a banner "Directory last synced X hours ago".

REQ-108: The Android app shall implement `PeopleScreen.kt` rendering the contact list from the `paired_bridge_contacts` table.

REQ-109: The `PeopleScreen` shall support search by display_name with a top search bar.

REQ-110: The `PeopleScreen` shall support filter by team/role with a chip-row above the list.

REQ-111: When the operator taps a contact row, the `PeopleScreen` shall navigate to a `ContactDetailScreen` showing addresses + group memberships + dispatch policy + trust dots.

REQ-112: When the operator taps a contact in `PeopleScreen` AND taps the "Compose" action, the app shall navigate to the existing Compose screen with the recipient pre-populated.

REQ-113: The Android app shall add a "Share via QR" action in `ContactDetailScreen` that calls `POST /api/directory/contacts/{id}/qr/handoff` per `meshsat/spec/006-android-directory-sync/` REQ-509 AND shall render the returned CBOR card as a QR code.

REQ-114: The Android app shall add a "Scan contact QR" action in `PeopleScreen` opening a CameraX viewfinder.

REQ-115: When a `meshsat://contact/...` QR scans successfully, the Android app shall POST the CBOR bytes to `POST /api/directory/contacts/import-handoff` on the active paired bridge per `meshsat/spec/006-android-directory-sync/` REQ-510.

REQ-116: When the handoff card import succeeds, the Android app shall trigger an immediate directory snapshot pull so the new contact appears in `PeopleScreen` within 5 seconds.

REQ-117: When the handoff card fails validation (TTL expired, signature invalid, replay), the Android app shall display an inline error AND shall NOT retry.

REQ-118: The Android app shall use `java.util.Base64` for all CBOR base64url handling per Constitution Article VI.

REQ-119: The Android `KeyBundleImporter` from `spec/001-tofu-bundle-v2/` shall remain unchanged; this phase introduces a parallel `ContactCardImporter` for directory handoff.

REQ-120: The `DirectorySyncService` shall be tested via JVM unit tests using stub DAO interfaces per Constitution Article VI requirement REQ-017 from `spec/001-tofu-bundle-v2/`.
