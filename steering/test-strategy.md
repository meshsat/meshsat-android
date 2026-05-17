# Steering — Test strategy

## Layout (per Article C-VIII)

ALL unit tests live FLAT at `app/src/test/java/com/cubeos/meshsat/<TestName>Test.kt`. Currently 45 test files. Examples covering each major package:

| Package | Test file (flat path under app/src/test/java/com/cubeos/meshsat/) |
|---|---|
| codec | `Smaz2Test.kt`, `PositionCodecTest.kt`, `CannedCodebookTest.kt` |
| crypto | `KeyBundleImporterTest.kt`, `AesGcmWireFormatTest.kt`, `NodeIdentityMergeTest.kt` |
| aprs | `AprsBeaconTest.kt`, `AprsCodecTest.kt`, `AprsIsClientTest.kt`, `AprsMessageTrackerTest.kt`, `Ax25CodecTest.kt` |
| ble | `MeshtasticBleTest.kt` (via integration-style mocks) |
| engine | `BurstQueueTest.kt`, `CreditTrackerTest.kt`, `GeofenceMonitorTest.kt`, `HealthScorerTest.kt`, `DeadManSwitchTest.kt`, `SigningServiceTest.kt`, `InterfaceManagerTest.kt`, `TelemetryLoggerTest.kt` |
| dtn | `BundleFragmenterTest.kt`, `BundleReassemblerTest.kt`, `CustodyManagerTest.kt` |
| fec / rlnc | `GaloisField256Test.kt`, `RlncDecoderTest.kt`, `ReedSolomonTest.kt` |
| hemb | `HembBonderTest.kt`, `HembBondGroupManagerTest.kt`, `BearerSelectorTest.kt` |
| reticulum | `RnsHdlcTest.kt`, `RnsInterfaceTest.kt`, `RnsTransportNodeTest.kt` |
| timesync | `TimeSyncProtocolTest.kt`, `MeshClockTest.kt` |
| satellite | `SatelliteTest.kt`, `PassPredictorTest.kt` |

## Why flat

Flat is what the repo already does. Mixing flat + nested would require IDE config + build-config changes for no value.

## Naming

`<ClassUnderTest>Test.kt`. One test class per file. Multiple `@Test` methods inside.

## Mocking + stubs

- Pure-Kotlin Mockk OR hand-rolled stubs.
- For DAO tests: `Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()` — runs in JVM via Robolectric OR pure Room test-utils.
- For Android-API surfaces: prefer Robolectric for JVM-runnable tests over instrumentation tests (faster CI).

## Running

```bash
# Whole suite
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:testDebugUnitTest'

# Single test class
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:testDebugUnitTest --tests com.cubeos.meshsat.KeyBundleImporterTest'

# Lint
ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew :app:lintDebug'
```

## Coverage expectation

- Every PR touching `crypto/`, `engine/`, `codec/`, `data/` shall have corresponding tests at the flat layout.
- UI screens may rely on `@Preview` parameter-passing tests + Compose-test instrumentation (separate from `testDebugUnitTest`).

## Anti-patterns

- Nested test packages — re-flatten before merge per Article C-VIII.
- Tests calling `Log.d` for assertions — use `assertEquals` / `assertTrue`.
- Tests requiring an emulator boot to pass `testDebugUnitTest` — those belong in `androidTest/` (instrumentation), not the unit-test suite.
