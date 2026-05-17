# Design — Android directory sync (spec/002)

## Why now

Phase 5 of `meshsat/EXECUTION-PLAN.md` — the Bridge already exposes a directory snapshot endpoint; Android needs to consume it so the operator sees the same contacts/groups/policies on phone as on bridge.

## Wire diagram

```
Bridge (meshsat)                              Android (meshsat-android)
                                              
 GET /api/directory/snapshot?since=N ◄─── DirectorySyncService.poll()
       │                                       │
       ▼                                       │
 Snapshot{contacts,addresses,         ───────► │ verify Ed25519 sig vs bridge_trust.pubkey
 groups,policies, signature}                   │     │
                                               │     ├─ valid → Room transaction:
                                               │     │            INSERT/UPDATE/DELETE
                                               │     │            paired_bridge_* rows
                                               │     │            for this bridge_hash
                                               │     └─ invalid → AuditLog WARN
                                               │
 publish meshsat/bridge/{hash}/        ──────► │ MQTT subscriber triggers
 directory/changed                             │ immediate pull within 5s
```

## File paths (all under existing package conventions)

| File | Path | Status |
|---|---|---|
| MIGRATION_14_15 | `app/src/main/java/com/cubeos/meshsat/data/AppDatabase.kt` | edit existing |
| PairedBridgeContactEntity | `app/src/main/java/com/cubeos/meshsat/data/PairedBridgeContactEntity.kt` | new |
| PairedBridgeAddressEntity | `app/src/main/java/com/cubeos/meshsat/data/PairedBridgeAddressEntity.kt` | new |
| PairedBridgeGroupEntity | `app/src/main/java/com/cubeos/meshsat/data/PairedBridgeGroupEntity.kt` | new |
| PairedBridgeDispatchPolicyEntity | `app/src/main/java/com/cubeos/meshsat/data/PairedBridgeDispatchPolicyEntity.kt` | new |
| PairedBridgeDirectoryDao | `app/src/main/java/com/cubeos/meshsat/data/PairedBridgeDirectoryDao.kt` | new |
| DirectorySyncService | `app/src/main/java/com/cubeos/meshsat/service/DirectorySyncService.kt` | new alongside `GatewayService.kt` + `TransportRegistry.kt` |
| PeopleScreen | `app/src/main/java/com/cubeos/meshsat/ui/screens/PeopleScreen.kt` | new alongside the 17 existing screens |
| ContactDetailScreen | `app/src/main/java/com/cubeos/meshsat/ui/screens/ContactDetailScreen.kt` | new |
| ContactQRDisplay | `app/src/main/java/com/cubeos/meshsat/ui/components/ContactQRDisplay.kt` | new alongside `StatusCard.kt` |
| ContactQRScanner | `app/src/main/java/com/cubeos/meshsat/ui/components/ContactQRScanner.kt` | new |
| ContactCardImporter | `app/src/main/java/com/cubeos/meshsat/crypto/ContactCardImporter.kt` | new alongside `KeyBundleImporter.kt`, same shape |
| Tests | `app/src/test/java/com/cubeos/meshsat/PairedBridgeDirectoryDaoTest.kt`, `DirectorySyncServiceTest.kt`, `ContactCardImporterTest.kt` | new at FLAT layout per Article C-VIII |

## Schema (Room v14→v15)

```sql
CREATE TABLE paired_bridge_contacts (
  bridge_hash TEXT NOT NULL,
  contact_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  notes TEXT,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (bridge_hash, contact_id),
  FOREIGN KEY (bridge_hash) REFERENCES bridge_trust(bridge_hash) ON DELETE CASCADE
);

CREATE TABLE paired_bridge_addresses (
  bridge_hash TEXT NOT NULL,
  contact_id TEXT NOT NULL,
  address_type TEXT NOT NULL,   -- 'meshtastic', 'iridium', 'sms', 'aprs', ...
  address_value TEXT NOT NULL,
  PRIMARY KEY (bridge_hash, contact_id, address_type, address_value),
  FOREIGN KEY (bridge_hash, contact_id) REFERENCES paired_bridge_contacts(bridge_hash, contact_id) ON DELETE CASCADE
);

CREATE TABLE paired_bridge_groups (
  bridge_hash TEXT NOT NULL,
  group_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  PRIMARY KEY (bridge_hash, group_id),
  FOREIGN KEY (bridge_hash) REFERENCES bridge_trust(bridge_hash) ON DELETE CASCADE
);

CREATE TABLE paired_bridge_dispatch_policies (
  bridge_hash TEXT NOT NULL,
  policy_id TEXT NOT NULL,
  precedence INTEGER NOT NULL,   -- STANAG 4406
  json_blob TEXT NOT NULL,
  PRIMARY KEY (bridge_hash, policy_id),
  FOREIGN KEY (bridge_hash) REFERENCES bridge_trust(bridge_hash) ON DELETE CASCADE
);
```

## Cross-references

- Bridge-side snapshot API: `meshsat/spec/002-unified-directory/contracts/openapi.yaml` (parent repo).
- Trust anchor: `bridge_trust` table from spec/001 (already shipped).
- Importer pattern: `crypto/KeyBundleImporter.kt` as template (already shipped).

## Out of scope

- Bridge-side directory schema (owned by `meshsat/spec/002-unified-directory/`).
- Compose-only directory editing on Android (read-only sync in v1; edit on Bridge UI).
- Cross-bridge contact merging (each bridge's contacts displayed as separate entries under the source bridge).
