# MeshSat Android -- Standalone Mobile Gateway & Reticulum Transport Node

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Native Android app that turns a phone into a standalone field gateway and full Reticulum Transport Node. Bridges Meshtastic mesh (BLE), Iridium satellite (9603N SBD + 9704 IMT), Astrocast LEO, APRS, cellular SMS, and MQTT -- with end-to-end encryption, semantic compression, and intelligent routing.

The phone IS the gateway. No companion app, no server dependency, no internet required.

## Transports

| Transport | Connection | Protocol | MTU |
|-----------|-----------|----------|-----|
| **Meshtastic** | Bluetooth LE | Official protobuf (15 portnums) | 237B |
| **Iridium 9603N** | HC-05 Bluetooth SPP | AT/SBD (19200 baud) | 340B |
| **Iridium 9704** | HC-05 Bluetooth SPP | JSPR (230400 baud, 100KB msgs) | 100KB |
| **Astrocast** | HC-05 Bluetooth SPP | Astronode S (auto-fragmentation) | 636B |
| **APRS** | KISS TNC + APRS-IS | AX.25 / APRS-IS TCP | 256B |
| **SMS** | Native Android | AES-GCM encrypted, MSVQ-SC compressed | 160B |
| **MQTT** | WiFi/cellular | Eclipse Paho (mTLS) | -- |

## Architecture

```
Phone (MeshSat Android v2.5.0)
 |
 +-- BLE ----------------> Meshtastic radio (15 portnums, full radio config)
 |
 +-- Bluetooth SPP ------> HC-05 --> RockBLOCK 9603N (Iridium SBD)
 |                      +-> HC-05 --> RockBLOCK 9704  (Iridium IMT/JSPR)
 |                      +-> HC-05 --> Astronode S      (Astrocast LEO)
 |
 +-- KISS TNC -----------> APRS radio (smart beaconing, directed messaging)
 +-- APRS-IS TCP --------> APRS internet gateway
 |
 +-- Native SMS ---------> Cellular (AES-GCM + MSVQ-SC compression)
 |
 +-- MQTT (mTLS) --------> MeshSat Hub (telemetry, commands, credentials)
 |
 +-- Reticulum TCP (TLS)-> reticulum.meshsat.net (Transport Node mesh)
 |
 +-- Dispatcher ---------> Per-interface workers, fan-out, dedup, TTL
 +-- AccessEvaluator ----> Cisco ASA-style ACL rules, keyword/sender filters
 +-- FailoverResolver ---> Priority-based transport failover groups
 +-- InterfaceManager ---> 5-state machine, exponential backoff reconnect
 +-- TransformPipeline --> compress -> encrypt -> base64 (per-interface)
 +-- BurstQueue ---------> TLV-pack multiple messages into one SBD (max 340B)
 +-- PassScheduler ------> 4-mode satellite scheduling (Idle/PreWake/Active/PostPass)
 +-- CreditTracker ------> Per-message Iridium cost tracking ($0.05/MO)
 +-- HealthScorer -------> Composite 0-100 per interface (signal/success/latency/cost)
```

## Reticulum Transport Node

MeshSat Android is a full Reticulum Transport Node -- not just a client. It relays packets between all interfaces, maintains a forwarding table with cost-aware routing, and announces itself to the mesh.

- **Ed25519 signing + X25519 encryption** identity
- **10+ Reticulum interfaces**: Meshtastic BLE, Iridium 9603, Iridium 9704, Astrocast, SMS, MQTT, TCP (HDLC), BLE peripheral (GATT server), Tor (SOCKS5), WireGuard
- **Cross-interface relay** with announce propagation and hop counting
- **3-packet ECDH link handshake** with AES-256-GCM encrypted links
- **Path table** with cost-aware forwarding and path request/response
- **TLS + mTLS** for authenticated TCP tunnel to Hub

## Meshtastic Integration

Full radio configuration via BLE (not just messaging):

- **15 portnums**: text, position, telemetry, routing ACK/NAK, waypoint, neighborinfo, traceroute, store-forward, range test, detection sensor, paxcounter, reply, nodeinfo, admin, private
- **7 config tabs**: Identity, LoRa (region/preset/TX power/hop limit), Channels (8 with PSK), Position (GPS/broadcast interval), Bluetooth, Network (WiFi), Admin (reboot/shutdown/factory reset)
- **Official protobuf bindings** from `meshtastic/protobufs` via `protobuf-javalite`

## Crypto & Compression

- **AES-256-GCM** per-conversation encryption for SMS
- **MSVQ-SC** lossy semantic compression (~92% savings) -- ONNX Runtime INT8 encoder (TX), pure Kotlin codebook decoder (RX)
- **Ed25519 hash-chain audit log** -- append-only, tamper-evident
- **ECDSA-P256 signed birth messages** for Hub device verification

## Field Intelligence

- **Dead Man Switch** -- auto-SOS after configurable inactivity timeout (default 4h)
- **Geofence Monitor** -- polygon zones with ray-casting, enter/exit events
- **Canned Codebook** -- 30 military brevity messages, 2-byte wire format
- **Position Codec** -- 16-byte full frame + 11-byte delta frame with DeltaEncoder
- **Smart APRS beaconing** -- corner-pegging algorithm adjusts beacon rate to movement

## Hub Integration

Connects to MeshSat Hub for centralized fleet management:

- **HubReporter** -- health telemetry, signed birth certificates, position updates
- **8 Hub commands**: ping, send_text, send_mt, flush_burst, config_update, reboot, credential_push, credential_revoke
- **mTLS client certificates** -- PEM import in Credentials screen
- **QR provisioning** -- scan Hub QR code to auto-configure all settings

## UI

Jetpack Compose Material 3 dark theme with:

- **Reorderable dashboard** -- sparklines, SOS button, location, queue depth, burst queue, activity log, Reticulum widget, credit gauge, mailbox check
- **Comms screen** -- message tabs with send/receive
- **Peers table** -- mesh node list with signal, position, last seen
- **Native map** -- osmdroid with markers, track lines, GPS accuracy circle, node filters, offline MBTiles support
- **Pass predictor** -- SGP4 orbital mechanics, TLE fetch, Canvas bezier elevation arcs, countdown
- **Interface management** -- transport status cards, channel list, health tabs
- **Rules editor** -- ACL rules, DLQ inspector, object groups, failover groups
- **Audit log** -- Ed25519-signed entries with chain verification
- **Full settings** -- all transports, routing config, announce intervals, mTLS, dead man switch, restart
- **Radio config** -- 7-tab Meshtastic device configuration

## Local REST API

NanoHTTPD server on `localhost:6051` with 14 endpoints including system restart.

## Config Management

JSON + YAML export/import (Bridge-compatible `show run`-style), validate, diff preview.

## Requirements

- **Android 8.0+** (API 26)
- Bluetooth LE (for Meshtastic)
- SMS permission (for cellular gateway)
- Location permission (for GPS, BLE scanning)

## Hardware

| Component | Purpose | Connection | Notes |
|-----------|---------|-----------|-------|
| Meshtastic radio | LoRa mesh | BLE | T-Deck, T-Echo, Heltec, XIAO+SX1262, etc. |
| RockBLOCK 9603 | Iridium SBD satellite | HC-05 SPP (19200 baud) | $0.05/msg, 340B max |
| RockBLOCK 9704 | Iridium IMT satellite | HC-05 SPP (230400 baud) | 100KB messages, JSPR protocol |
| Astronode S | Astrocast LEO satellite | HC-05 SPP | Auto-fragmentation up to 636B |
| APRS radio | VHF packet radio | KISS TNC | Quansheng UV-K5, Baofeng, etc. |
| HC-05 adapter | Bluetooth-to-serial bridge | Bluetooth SPP | Set baud before use |
| Android phone | Gateway host | Built-in cellular, BLE, GPS | SMS, location, notifications |

## Build

Requires Android SDK (compileSdk 35) and JDK 17.

```bash
./gradlew assembleDebug
```

## Tech Stack

| Component | Library |
|-----------|---------|
| Language | Kotlin |
| UI | Jetpack Compose (Material 3) |
| Database | Room (SQLite, schema v10) |
| Settings | DataStore Preferences |
| Secure storage | EncryptedSharedPreferences (AndroidX Security) |
| ML Runtime | ONNX Runtime Android (INT8 quantized) |
| Map | osmdroid (native OpenStreetMap) |
| MQTT | Eclipse Paho |
| Protobuf | protobuf-javalite (Meshtastic bindings) |
| HTTP server | NanoHTTPD |
| QR scanning | ZXing Android Embedded |
| Crypto | BouncyCastle (Ed25519/X25519 JCA provider) |
| Concurrency | Kotlin Coroutines |

## Assets (~35MB)

| File | Size | Purpose |
|------|------|---------|
| `encoder.onnx` | 22MB | INT8 quantized MSVQ-SC sentence encoder |
| `codebook_v1.bin` | 12MB | ResidualVQ codebook vectors |
| `corpus_index.bin` | 70KB | Nearest-neighbor decode corpus |
| `vocab.txt` | 230KB | WordPiece vocabulary (30,522 tokens) |

## Data Layer

Room database (schema v10) with 12 entities: Messages, SignalRecord, NodePosition, ForwardingRuleEntity, ConversationKey, AccessRuleEntity, ObjectGroupEntity, FailoverGroupEntity, MessageDeliveryEntity, AuditLogEntity, RnsTcpPeer, IridiumCreditEntry.

DataStore for all settings including encryption keys, transport config, routing parameters, mTLS certificates, and dashboard layout.

## Related Projects

- [MeshSat Bridge](https://github.com/cubeos-app/meshsat) -- Go gateway for Raspberry Pi / Linux (same transport suite, USB devices)
- [MeshSat Hub](https://github.com/cubeos-app/meshsat-hub) -- Multi-tenant SaaS fleet management platform
- [MeshSat Website](https://meshsat.net) -- Documentation, install scripts

## License

Copyright 2026 Nuclear Lighters Inc. Licensed under the [Apache License 2.0](LICENSE).
