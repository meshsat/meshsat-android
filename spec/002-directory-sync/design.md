# Design — Android Directory Sync

## Goal

When Android is paired with a bridge, the operator's People view should match the bridge's view. The sync pulls a signed snapshot from `GET /api/directory/snapshot` (bridge side: `meshsat/spec/006-android-directory-sync/`), validates against the pinned `bridge_trust` pubkey from `spec/001-tofu-bundle-v2/`, and applies to Room.

## Wire diagram

```
   Android                                Bridge
     │                                      │
     │  (initial pairing in spec/001)       │
     │ ──────────────────────────────────▶  │
     │ ◀── bridge_trust pubkey pinned ──── │
     │                                      │
     │  GET /api/directory/snapshot?        │
     │      since=last_version              │
     │ ──────────────────────────────────▶  │
     │ ◀── signed JSON snapshot ────────── │
     │                                      │
     │  verify Ed25519 sig with             │
     │  pinned bridge_trust pubkey          │
     │                                      │
     │  apply to paired_bridge_* tables     │
     │  in single transaction               │
     │                                      │
     │  PeopleScreen renders contacts       │
     │                                      │
     │ ◀── meshsat/bridge/{id}/             │
     │     directory/changed (MQTT) ────── │
     │  trigger fresh pull                  │
```

## Tables (Room v15)

Mirrors the bridge schema from `meshsat/spec/002-unified-directory/`:
- `paired_bridge_contacts(bridge_id PK, contact_id PK, display_name, team, sidc, trust_level, ...)`
- `paired_bridge_addresses(bridge_id, contact_id, address_id PK, kind, value, ...)`
- `paired_bridge_groups(bridge_id, group_id PK, name, ...)`
- `paired_bridge_dispatch_policies(bridge_id, policy_id PK, scope_type, scope_id, strategy, ...)`

Composite primary keys include `bridge_id` so multi-bridge scenarios (from `spec/003-pair-shell/`) work cleanly.

## QR contact-card handoff (REQ-113..117)

Symmetric with the bridge-bridge handoff from `meshsat/spec/006-android-directory-sync/`:
- **Export:** Android calls bridge's `POST /api/directory/contacts/{id}/qr/handoff` → bridge returns CBOR card → Android renders as QR for in-person showing.
- **Import:** Android scans a QR → POSTs the CBOR bytes to its own paired bridge's `POST /api/directory/contacts/import-handoff` → bridge validates + adds to its directory → directory_push notification triggers Android sync → contact appears in PeopleScreen.

## Out of scope

- Bridge-to-bridge handoff itself (bridge side spec/006).
- Pair-protocol initial pairing (spec/001-tofu-bundle-v2).
- Multi-bridge UI (spec/003-pair-shell + meshsat/spec/009-multi-bridge-nat).
- Compose screen integration (Android already has it; this spec only navigates to it with pre-populated recipient).
