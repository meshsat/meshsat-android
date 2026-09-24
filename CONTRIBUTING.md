# Contributing to MeshSat Android

## Build Setup

1. Install JDK 17
2. Install Android SDK (API 26+)
3. Clone the repo and build:

```bash
./gradlew assembleFdroidDebug
```

The debug APK will be in `app/build/outputs/apk/fdroid/debug/`. There are two flavors: `fdroid` is the full app, `play` the Google Play edition without SMS (`assemblePlayDebug`).

## Code Style

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3, dark theme)
- **Async:** Kotlin Coroutines (no RxJava, no callbacks)
- **Database:** Room with suspend functions
- Follow standard Kotlin conventions and Android best practices

## Testing Transports

Each transport requires specific hardware to test properly.

### Meshtastic BLE
- Requires a physical Meshtastic radio (T-Deck, T-Echo, Heltec, etc.)
- Pair via BLE from the app's scan screen
- Test: send a message from the app, confirm it appears on the radio (and vice versa)

### Iridium (RockBLOCK 9603 on a MeshSat node)
- Requires a MeshSat node: a Meshtastic radio running meshsat-firmware with a RockBLOCK 9603
  on its serial port (see github.com/meshsat/meshsat-esp32). The app reaches the modem over the
  node's Bluetooth Iridium service, on the same connection as the mesh; no HC-05 since 2.9.0.
- Pair the node in the app: Setup > Your MeshSat node. Setup > Satellite shows the modem.
- Free check with no satellite session: `POST http://127.0.0.1:6051/api/iridium/loopback?size=270`
  (via `adb forward tcp:6051 tcp:6051`) writes, copies and reads back 270 bytes on the modem.
- Test: send a message by satellite, confirm delivery in the Rock7 portal or on the Hub. Every
  session that reaches the satellite network uses at least one credit.

### Iridium (RockBLOCK 9704)
- Requires an HC-05/06 Bluetooth serial adapter wired to a RockBLOCK 9704 (JSPR over serial),
  paired in Android's Bluetooth settings first. Not yet tested on hardware.

### SMS
- Requires a phone with an active SIM card and SMS permissions granted
- Test: send an encrypted message to another MeshSat instance (Android or Pi)
- Verify AES-256-GCM round-trip (both sides must share the same key)

## Hardware Testing Guidelines

When reporting test results for hardware, include:

- **Device model** (phone make/model)
- **Android version** (e.g., Android 14, API 34)
- **BLE chipset** (if known, from device specs)
- **Radio firmware version** (for Meshtastic radios)
- **Node firmware version** (meshsat-firmware build, if testing the satellite path)

## Pull Request Guidelines

- Describe what changed and why
- If the change touches a transport (BLE, SPP, SMS), test on real hardware before submitting
- Keep PRs focused -- one feature or fix per PR
- Ensure `./gradlew assembleFdroidDebug` passes before submitting

## License

By contributing, you agree that your contributions will be licensed under the
GNU General Public License v3.0, the licence in [LICENSE](LICENSE).

This repository previously stated Apache-2.0 here. That was an error: the app
vendors GPL-3.0 Meshtastic protobuf definitions, so Apache-2.0 was never a
licence this project could grant.
