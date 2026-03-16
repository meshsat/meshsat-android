# Contributing to MeshSat Android

## Build Setup

1. Install JDK 17
2. Install Android SDK (API 26+)
3. Clone the repo and build:

```bash
./gradlew assembleDebug
```

The debug APK will be in `app/build/outputs/apk/debug/`.

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

### Iridium SPP
- Requires an HC-05 Bluetooth adapter wired to a RockBLOCK 9603N / Iridium 9603N
- HC-05 must be set to 19200 baud (`AT+UART=19200,0,0`)
- Pair the HC-05 via Android Bluetooth settings first
- Test: send an SBD message, confirm delivery via RockBLOCK portal or receiving device

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
- **HC-05 firmware version** (if testing Iridium path)

## Pull Request Guidelines

- Describe what changed and why
- If the change touches a transport (BLE, SPP, SMS), test on real hardware before submitting
- Keep PRs focused -- one feature or fix per PR
- Ensure `./gradlew assembleDebug` passes before submitting

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
