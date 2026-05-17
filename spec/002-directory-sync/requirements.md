# Requirements — Android directory sync (spec/002)

Source: `meshsat/EXECUTION-PLAN.md` §6.5 (Phase 5 — Android directory sync from Bridge) + ADR-0005 (signed key bundles as the trust anchor) + parent CubeOS `docs/spec/006-android-directory-sync/`.

> Future-work. ID convention: 100-block (`100..199`).

## Schema delta (Room v14→v15)

REQ-100: The system shall add a Room migration `MIGRATION_14_15` introducing four new entities under the `data/` package: `PairedBridgeContactEntity`, `PairedBridgeAddressEntity`, `PairedBridgeGroupEntity`, `PairedBridgeDispatchPolicyEntity`.
REQ-101: When `MIGRATION_14_15` runs, the system shall create tables `paired_bridge_contacts`, `paired_bridge_addresses`, `paired_bridge_groups`, `paired_bridge_dispatch_policies` with foreign-key references to `bridge_trust.bridge_hash`.
REQ-102: The system shall append `MIGRATION_14_15` to the existing migration list per Article C-IV.

## DirectorySyncService

REQ-103: The system shall add `service/DirectorySyncService.kt` alongside the existing `service/GatewayService.kt` and `service/TransportRegistry.kt`.
REQ-104: When the service is started, the system shall iterate every paired bridge in `bridge_trust` and issue a GET https://<bridge>/api/directory/snapshot?since=<last_sync_at>.
REQ-105: When the service receives a snapshot response, the system shall verify the Ed25519 signature against the stored `bridge_trust.pubkey` for that bridge.
REQ-106: If the snapshot signature verification fails, then the system shall log a WARN event to AuditLog and not apply the snapshot.
REQ-107: When verification succeeds, the system shall apply the snapshot inside a single Room transaction touching only the `paired_bridge_*` tables for that bridge_hash.
REQ-108: While running, the system shall subscribe to the MQTT topic `meshsat/bridge/{bridge_hash}/directory/changed` and trigger an immediate snapshot pull within 5 seconds of receiving any event.

## People + ContactDetail screens

REQ-109: The system shall add `ui/screens/PeopleScreen.kt` alongside the existing 17 screens, showing the union of contacts from every paired bridge with search + filter.
REQ-110: When the operator taps a contact, the system shall navigate to `ui/screens/ContactDetailScreen.kt` showing the contact's addresses, groups, dispatch policies, and source bridge_hash.
REQ-111: While the `paired_bridge_contacts` table is older than 24 hours, the system shall display a "stale data — last synced X ago" banner on PeopleScreen.

## QR export + import

REQ-112: The system shall add `ui/components/ContactQRDisplay.kt` rendering a contact card as a base64-encoded vCard QR for sharing.
REQ-113: The system shall add `ui/components/ContactQRScanner.kt` for ingesting contact-card QRs from other operators.
REQ-114: When the operator scans a contact-card QR, the system shall call `crypto/ContactCardImporter.kt` to verify the embedded signature (Ed25519, via "BC" provider per Article C-II) before persisting.
REQ-115: If contact-card signature verification fails, then the system shall surface an inline error in the UI and not persist.

## Importer pattern parity

REQ-116: The system shall implement `crypto/ContactCardImporter.kt` following the same shape as the shipped `crypto/KeyBundleImporter.kt` — sealed-class `ImportResult` inline, explicit `"BC"` provider, no JNI.
REQ-117: The system shall NOT modify or replace `crypto/KeyBundleImporter.kt` while adding `ContactCardImporter.kt`.

## Tests

REQ-118: The system shall add a JVM-runnable test `app/src/test/java/com/cubeos/meshsat/PairedBridgeDirectoryDaoTest.kt` covering insert + query + foreign-key cascade for the four new entities (flat layout per Article C-VIII).
REQ-119: The system shall add a JVM-runnable test `app/src/test/java/com/cubeos/meshsat/DirectorySyncServiceTest.kt` covering: successful sync, signature failure, MQTT-trigger pull, stale-data banner threshold (flat layout per Article C-VIII).
REQ-120: The system shall add a JVM-runnable test `app/src/test/java/com/cubeos/meshsat/ContactCardImporterTest.kt` covering: valid QR import, tampered-sig rejection, malformed-payload rejection (flat layout per Article C-VIII).
