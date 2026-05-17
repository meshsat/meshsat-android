# Constitution — MeshSat Android

Inherits the full CubeOS project-level constitution at `/home/claude-runner/gitlab/products/cubeos/docs/constitution.md`. This file adds Android-specific articles (C-I through C-X). All facts CGC-verified 2026-05-18.

## Article C-I — Build host is `nllei01androidsdk01`

The system shall build, test, and lint via the SDK host. The Gradle wrapper invocation MUST use `ssh ansible@nllei01androidsdk01 'cd /home/claude-runner/gitlab/products/cubeos/meshsat-android && ./gradlew <task>'`. Local workstation builds are not supported (no SDK + JDK 17 wiring assumed). See ADR-0002.

## Article C-II — BouncyCastle MUST be `addProvider`, NEVER `insertProviderAt`

The system shall add BouncyCastle (or any third-party crypto provider) via `Security.addProvider(BouncyCastleProvider())` — appending to the provider list. The system shall NEVER `insertProviderAt(BC, 1)` or any priority < tail. Reason: inserting at priority 1 displaces Android's built-in providers used by other libraries (OkHttp, WorkManager) and silently breaks TLS verification across the app. Audit `crypto/*.kt` on every change. Existing call sites: `crypto/KeyBundleImporter.kt` (uses `"BC"` provider explicitly per Signature.getInstance), `crypto/AesGcmCrypto.kt`.

## Article C-III — `CGO_ENABLED=0` equivalent: no JNI for crypto

The system shall NOT use JNI for cryptographic operations. Crypto goes through `java.security` / BouncyCastle / Android Keystore. This eases supply-chain auditing and avoids ABI breakage on Android updates. Pure-Kotlin / pure-Java only.

## Article C-IV — Single Room migration per version

The system shall add exactly ONE `Migration(N, N+1)` per `AppDatabase` version bump. The Room migration list in `data/AppDatabase.kt` is append-only — older migrations are NEVER edited. Currently migrations 5→6 through 13→14 are shipped; next bump is 14→15 (which spec/002 adds). The `bridge_trust` table was added in `MIGRATION_12_13` (shipped 2026-03 as part of TOFU/Bundle v2).

## Article C-V — Android Keystore is the ONLY home for client private keys

The system shall store the X25519 + Ed25519 client private keys generated during pair-claim in the Android Keystore (`AndroidKeyStore` provider) via `KeyPairGenerator.getInstance("EC", "AndroidKeyStore")`. The raw private key bytes shall NEVER appear in SharedPreferences, in Room columns, in JSON exports, in log statements, or in backup payloads. Use `crypto/SecureKeyStore.kt` as the canonical wrapper.

## Article C-VI — JWTs in `EncryptedSharedPreferences`, never plain prefs

The system shall persist bearer JWTs in `EncryptedSharedPreferences` (AndroidX Security). Never `SharedPreferences`. The encrypted file lives at `app/src/main/...` location managed by androidx.security.crypto.

## Article C-VII — DAO interfaces must be JVM-testable

The system shall expose every Room DAO as an `interface` with abstract methods (Room generates the impl). Tests use the actual Room generated impl with in-memory database (`Room.inMemoryDatabaseBuilder`). No Android-instrumentation tests for DAO logic — JVM-only via `:app:testDebugUnitTest`.

## Article C-VIII — Flat test layout

The system's unit tests shall live FLAT at `app/src/test/java/com/cubeos/meshsat/<TestName>Test.kt`. NO nested test packages (no `app/src/test/.../crypto/<TestName>Test.kt`, no `.../data/<TestName>Test.kt`). Mirrors the 45 existing tests (BirthSigner, RnsHdlc, KeyBundleImporter, CreditTracker, HembBonder, BurstQueue, etc.).

## Article C-IX — Compose Material 3 + Kotlin coroutines

The system's UI shall use Jetpack Compose with Material 3. No View-based XML layouts for new screens. State propagation via Kotlin coroutines `Flow` + Compose `collectAsState`. New screens go in `ui/screens/<Name>Screen.kt`; new components in `ui/components/<Name>.kt`; new top-level UI orchestrators directly under `ui/` (only existing example: `MeshSatUI.kt`).

## Article C-X — Compose preview vs runtime parity

The system shall keep `@Preview` composables in the same file as the production composable they preview. Previews shall pass mock data via parameters; the runtime composable accepts the same parameter signature. This makes UI work iteratively testable in Android Studio without device emulator boot.
