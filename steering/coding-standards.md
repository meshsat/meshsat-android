# Steering — Coding standards

CGC-verified against the 269 source files / 9,993 functions / 680 classes / 18 Room entities of the meshsat-android repo as of 2026-05-18.

## Package conventions

Source: `app/src/main/java/com/cubeos/meshsat/<pkg>/<File>.kt`.

29 top-level packages exist + root (MainActivity + MeshSatApp). New code MUST go in an existing package OR justify a new one in the commit message.

| Functional group | Packages | Examples |
|---|---|---|
| Transports | `aprs`, `astrocast`, `ble`, `bt`, `sms`, `mqtt`, `tak`, `satellite` | `aprs/AprsBeacon.kt`, `ble/MeshtasticBle.kt` |
| Codec + crypto | `codec`, `crypto`, `fec`, `rlnc` | `codec/Smaz2.kt`, `crypto/AesGcmCrypto.kt` |
| Routing engine | `engine`, `reticulum`, `routing`, `rules`, `dtn`, `hemb`, `dedup`, `ratelimit` | `engine/Dispatcher.kt`, `hemb/HembBonder.kt` |
| Data layer | `data` | `data/AppDatabase.kt`, `data/Message.kt` |
| Service layer | `service` | `service/GatewayService.kt` |
| Local API | `api` | `api/LocalApiServer.kt` |
| Config | `config` | `config/ConfigManager.kt` |
| UI | `ui`, `ui/screens`, `ui/components`, `ui/theme` | `ui/MeshSatUI.kt`, `ui/screens/DashboardScreen.kt` |
| Cross-MeshSat | `hub`, `map`, `timesync`, `channel` | `hub/...`, `timesync/TimeSource.kt` |

## Class naming

- Entities: `<Name>Entity.kt` (e.g. `BridgeTrustEntity.kt`, `ForwardingRuleEntity.kt`)
- DAOs: `<Name>Dao.kt` (e.g. `MessageDao.kt`, `BridgeTrustDao.kt`)
- Repositories: `<Name>Repository.kt` (e.g. `ConversationKeyRepository.kt`)
- Composable screens: `<Name>Screen.kt` under `ui/screens/`
- Composable components: `<Name>.kt` under `ui/components/`
- Tests: `<Name>Test.kt` FLAT at `app/src/test/java/com/cubeos/meshsat/`

## Coroutines

- Long-running work runs in `Dispatchers.IO` or a dedicated `CoroutineScope` owned by `GatewayService`.
- Compose state via `Flow.collectAsState()`.
- DAO `suspend fun` for writes; `Flow<T>` for reactive reads.

## Logging

- `android.util.Log` for app-level logs (tagged with the class name).
- NEVER log raw keys, JWTs, passwords. Use the `[REDACTED]` marker if a value path might surface in logs.
- `RnsHdlc` / `RnsTransportNode` / `RnsInterface` style: per-class TAG constant.

## Imports

- AndroidX only — no v4/v7 support libraries.
- BouncyCastle via `org.bouncycastle.*` (not the `org.spongycastle.*` fork).
- Compose via `androidx.compose.*`.
- NanoHTTPD for `api/LocalApiServer.kt` (already a dependency).

## Forbidden patterns

- `Security.insertProviderAt(BouncyCastleProvider(), N)` for any `N` — use `addProvider` per Article C-II.
- Raw key bytes in `SharedPreferences` — use `EncryptedSharedPreferences` OR Android Keystore per Article C-V/C-VI.
- View-based XML layouts for new screens — use Compose per Article C-IX.
- Nested test packages — flat layout per Article C-VIII.
