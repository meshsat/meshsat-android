# MeshSat Android -- Mobile Satellite + Mesh Gateway

[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Native Android app that acts as a standalone mobile gateway bridging Meshtastic mesh (BLE), Iridium 9603N satellite (HC-05 Bluetooth SPP), and cellular SMS. The phone IS the gateway in the field.

## Features

- **Meshtastic BLE radio connection** -- scan, connect, send/receive mesh messages
- **Iridium 9603N via HC-05 Bluetooth SPP** -- SBD send/receive, signal polling
- **SMS gateway with AES-256-GCM encryption** -- per-conversation keys
- **MSVQ-SC lossy semantic compression** -- 92% savings on field messages
- **Forwarding rules engine** -- mesh<->iridium<->SMS routing
- **Node map** with phone GPS + mesh node positions
- **6-hour signal history graphs** -- mesh RSSI, Iridium signal, cellular dBm
- **SOS emergency broadcast** -- mesh + Iridium + SMS, 3x with 30s interval
- **Push notifications** for incoming messages
- **Material 3 dark theme UI** (Jetpack Compose)

## Architecture

```
Phone (MeshSat-Android)
 |
 +-- BLE -----------> Meshtastic radio
 |
 +-- Bluetooth SPP --> HC-05 --> RockBLOCK 9603N (Iridium)
 |
 +-- Native SMS -----> Cellular network
 |
 +-- Rules engine + AES-256-GCM encryption
 |
 +-- MSVQ-SC semantic compression (ONNX Runtime)
```

## Requirements

- **Android 8.0+** (API 26)
- Bluetooth LE
- SMS permissions

## Hardware

| Component | Purpose | Notes |
|-----------|---------|-------|
| Meshtastic radio | LoRa mesh | Any BLE-capable device (T-Deck, T-Echo, Heltec, etc.) |
| Iridium 9603N / RockBLOCK 9603 | Satellite modem | Needs separate power (up to 1.5A during TX) |
| HC-05 Bluetooth adapter | Bluetooth-to-serial bridge | 3.3V TTL, set to 19200 baud |
| Android phone | Gateway host | Built-in cellular modem for SMS |

### HC-05 to RockBLOCK Wiring

```
HC-05 TX  -->  RockBLOCK RX (pin 6)
HC-05 RX  <--  RockBLOCK TX (pin 5)
HC-05 VCC -->  3.3V
HC-05 GND -->  GND
```

Set HC-05 baud rate before use: `AT+UART=19200,0,0`

## Build

Requires Android SDK and JDK 17.

```bash
./gradlew assembleDebug
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Database:** Room (SQLite)
- **ML Runtime:** ONNX Runtime (MSVQ-SC compression)
- **Concurrency:** Kotlin Coroutines
- **Min SDK:** Android 8.0 (API 26)

## MeshSat-to-MeshSat Encrypted SMS

MeshSat-Android and [MeshSat](https://github.com/cubeos-app/meshsat) (Pi/Linux) share the same AES-256-GCM + base64 codec, enabling encrypted peer-to-peer communication over SMS. Two mesh networks can be bridged over cellular:

```
[Mesh A] --LoRa--> [MeshSat Pi] --encrypted SMS--> [MeshSat-Android] --BLE--> [Mesh B]
```

Both nodes are peers -- configure the same encryption key on both sides and set up forwarding rules to route between mesh and SMS. The cellular link becomes a transparent, encrypted bridge between two otherwise isolated LoRa mesh networks.

## Related Projects

- [MeshSat](https://github.com/cubeos-app/meshsat) -- Go Pi/Linux gateway (same concept, USB devices instead of Bluetooth)
- [Meshtastic-Android](https://github.com/meshtastic/Meshtastic-Android) -- Official Meshtastic app (BLE protocol reference)

## Status

Early development. Tracking issue: CUBEOS-70.

## License

Copyright 2026 Nuclear Lighters Inc. Licensed under the [Apache License 2.0](LICENSE).
