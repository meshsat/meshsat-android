# Steering — Coding standards (MeshSat Android)

## Kotlin

- Kotlin 2.1.0, jvmTarget 17. Compose BOM 2024.12.01. AGP 8.7.3. KSP 2.1.0-1.0.29.
- Package: `com.cubeos.meshsat.*` (and subpackages by bounded context).
- File naming: PascalCase Kotlin files. One top-level class per file. Suffix conventions: `*Service.kt`, `*Repository.kt`, `*Screen.kt`, `*ViewModel.kt`.
- Coroutines: structured concurrency via `viewModelScope` / `lifecycleScope`. Cancellable via `Job` references. Long-running gateway threads via dedicated `CoroutineScope` in `GatewayService`.
- Flow: use `StateFlow` for UI state, `SharedFlow` for events. Debounce rapid updates (e.g. `getLatestPerNode()` 500ms debounce in Room).
- Null safety: prefer `?.` chains. `!!` requires comment justifying.
- Logging: `android.util.Log` for production paths, NOT in JVM-test-reachable code (Article VI). Use `slf4j-android` if cross-paradigm.

## Compose

- Material 3, dark theme primary (`isDark = true` default).
- Navigation Compose 2.8.5 for tab navigation. **MapScreen lives OUTSIDE NavHost** (Article IV).
- State holders preferred over inline `remember` for non-trivial state.
- `rememberSaveable` for any UI state that should survive configuration changes (Article XIV).
- Composables are testable in JVM via Robolectric — but instrumented tests don't exist (no `androidTest/` dir).

## Build (single Gradle module `:app`)

- `app/build.gradle.kts` is the canonical config. No multi-module — package boundaries are package-scoped, not module-scoped.
- `noCompress += listOf("onnx", "bin", "mbtiles")` is required for ONNX Runtime raw file access — keep.
- `isMinifyEnabled = true` in release. `proguard-rules.pro` keep rules are load-bearing (Article VII).
- Signing config secrets live in OpenBao env vars (CI-only). Local builds use debug signing.

## Comments + docs

- KDoc on public APIs of `:app` packages.
- Per-package `package-info.kt` for non-obvious subsystems.
- CLAUDE.md (368 lines, gitignored) is operator-only — never reference from committed code.
- CGC (CodeGraphContext MCP) can't analyze Kotlin — cross-reference work must be done by direct grep / Read.

## Imports

- Group order: stdlib → AndroidX → third-party → local.
- No wildcard imports outside test files.
