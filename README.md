# MeshSat Android

[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-green)](LICENSE)

Mobile gateway app that bridges Meshtastic mesh networks, Iridium SBD satellite, and cellular SMS from a single Android phone.

## What It Does

- Connects to a Meshtastic radio via BLE (T-Deck, T-Echo, etc.)
- Connects to a RockBLOCK 9603N Iridium modem via Bluetooth Serial (HC-05/HC-06 bridge)
- Sends and receives SMS natively
- Routes messages between any pair of transports using a configurable rules engine
- Encrypts messages with AES-256-GCM for secure SMS transport
- Predicts Iridium satellite passes using SGP4/TLE propagation
- Manages an SBD delivery queue with ISU-aware retry and backoff

## Architecture

```
Phone (MeshSat-Android)
 |
 +-- BLE -----------> Meshtastic radio
 |
 +-- Bluetooth SPP --> HC-05/06 --> RockBLOCK 9603N (Iridium)
 |
 +-- Native SMS -----> Cellular network
 |
 +-- Rules engine + AES-256-GCM encryption
```

## Hardware

| Component | Purpose | Notes |
|-----------|---------|-------|
| Meshtastic radio | LoRa mesh | Any BLE-capable device (T-Deck, T-Echo, etc.) |
| HC-05 or HC-06 | Bluetooth-to-serial bridge | 3.3V TTL, set to 19200 baud |
| RockBLOCK 9603N | Iridium SBD modem | Needs separate power (up to 1.5A during TX) |
| Android phone | Gateway host | Android 8+ (API 26), BLE + Bluetooth Classic |

### HC-05 to RockBLOCK Wiring

```
HC-05 TX  -->  RockBLOCK RX (pin 6)
HC-05 RX  <--  RockBLOCK TX (pin 5)
HC-05 VCC -->  3.3V
HC-05 GND -->  GND
```

Set HC-05 baud rate before use: `AT+UART=19200,0,0`

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** Android 8.0 (API 26)
- **BLE:** Android BLE API (Meshtastic protobuf protocol)
- **Bluetooth Serial:** BluetoothSocket (Classic SPP)
- **SMS:** SmsManager + BroadcastReceiver
- **Database:** Room (SQLite)
- **Crypto:** javax.crypto (AES-256-GCM)
- **Satellite prediction:** SGP4/TLE

## Related Projects

- [MeshSat](https://github.com/cubeos-app/meshsat) -- Pi/Linux gateway (same concept, USB devices instead of Bluetooth)
- [Meshtastic-Android](https://github.com/meshtastic/Meshtastic-Android) -- Official Meshtastic app (BLE protocol reference)

## Status

Early development. Tracking issue: CUBEOS-70.

## License

Copyright 2026 Nuclear Lighters Inc. Licensed under the [GNU General Public License v3.0](LICENSE).
