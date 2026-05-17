# ADR-0007 — Hybrid Compose + WebView pair shell (Phase 8): native for hot paths, WebView for Settings/Engineer surfaces

* Status: Proposed — design committed 2026-04-18 in `meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md` §5.2 + §11 (Phase 8, weeks 21-24); implementation pending. Mirror of `meshsat/adr/0004-three-shell-spa-architecture.md` from the Android perspective.
* Date: Originally decided 2026-04-18; recorded as ADR 2026-05-17.
* Deciders: `ufwtqkgz@meshsat.net`
* Source documents: `meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md` §5.2 + §11, `meshsat/EXECUTION-PLAN.md` §6.8 (Phase 8 stories S8-07 through S8-15)

## Context

The Bridge's three-shell SPA architecture (`meshsat/adr/0004-three-shell-spa-architecture.md`) commits one Vue codebase to three shells: Kiosk, Desktop browser, and **Android**. On the Android side, that decision has a follow-on question: what is *Android*'s native code vs what is *the Bridge's SPA wrapped in a WebView*?

Two extremes:

1. **Pure native Compose for everything** (no WebView): MeshSat Android reimplements all 14 Settings / Interfaces / Rules / Audit / Spectrum tabs in Kotlin. Maximum native feel, but ~17 surface-area-tabs of duplicate UI to maintain. Every Bridge UI change requires a corresponding Android Kotlin change.
2. **Pure WebView (no native Compose)**: MeshSat Android wraps the Bridge's Vue SPA in a single WebView. Zero native UI to maintain, but loses native feel on hot paths (Compose / Inbox / Map / People) where mobile-keyboard / native-gesture / native-notification integration matters.

The Home Assistant Companion app demonstrates a third option that scales: **hybrid** — native for hot paths, WebView for cold paths.

## Decision

**MeshSat Android Phase 8 ships a hybrid shell**:

### Native Jetpack Compose UI for hot paths (used every session, latency-sensitive)
- **Paired-bridges list** — tile view with green/amber/red health dots
- **Bridge switcher** — swipe or top-drawer "this phone / paired bridge 1 / paired bridge 2 ..."
- **Compose** — people picker + precedence + message body
- **Inbox** — conversation list + thread, bearer-coloured bubbles, per-bearer delivery ticks
- **Map** — osmdroid (already in repo, well-optimized — Article IV's "MapScreen OUTSIDE NavHost" rule applies)
- **People** — search + filter + verify

### WebView wrapping the Bridge's own Vue SPA for cold paths (used rarely, high surface area)
- **Settings** (17 tabs)
- **Interfaces / Rules / Topology / Audit / Spectrum / ZigBee detail**
- **Anything Engineer Mode** — implicit cost-saver: Engineer-Mode users don't need native-feel; they need the Bridge's full surface

### Cross-cutting concerns
- WebView preloaded with `Authorization: Bearer <JWT>` and a pinned TLS handler via OkHttp `CertificatePinner` interceptor; verifies Bridge SPKI == stored `cert_sha256`.
- Cert + JWT storage: Android Keystore (hardware-backed on Pixel, Samsung secure element, etc.).
- Paired-bridges Room DB: `paired_bridges` table per `UX-MULTI-ACCESS-KIOSK-PAIRING.md` §5.3 (sql shown there).
- Foreground service holds SSE connection from the active bridge for native notifications (Phase 9, MESHSAT-S9-03).

### "Both" mode (decision #2 of `meshsat/EXECUTION-PLAN.md §0`)
- First-run splash offers three options: Standalone / Pair with Bridge / Both.
- "Both" means the phone runs its own standalone gateway AND is paired with ≥ 1 Bridge.
- Top **Source switcher** lets the operator pick which source they're reading from.
- Contacts and messages are per-source.

## Consequences

**Positive**
- Settings + Interfaces + Rules + Audit are **automatically up-to-date** on Android the moment the Bridge SPA changes — zero Android Kotlin work for those updates.
- Hot paths (Compose / Inbox / Map / People) get native feel — native keyboard, native gestures, native notifications.
- Cert pinning via OkHttp interceptor is the same pattern WebView uses for HTTPS — one trust model across the app.
- The WebView's bearer JWT is per-session; loss of the phone doesn't extend trust beyond the cert revocation window (Bridge can also revoke the paired client per `meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md §4.6`).
- "Both" mode is operationally powerful — one phone can be a standalone gateway in the field AND a paired remote for the team's Bridge in the truck.

**Negative**
- Two UI mental models for contributors — Kotlin Compose for hot paths, Vue for cold paths. Mitigation: WebView paths use the Bridge SPA directly, so Vue knowledge is required only on the Bridge side.
- WebView cert pinning is fragile across Android versions — `WebViewClient.shouldInterceptRequest` lets us do pinning, but per-API-level differences are real. Mitigation: integration tests on Pixel 9a (Android 16) for the WebView paths.
- The "Source switcher" UI is novel — operator confusion risk if not designed carefully. Mitigation: explicit "Source: this phone" / "Source: tesseract" header on every view; notifications tagged with source ID.

**Forward direction**
- Pair-protocol v2 (next major rev) could allow Android to act as a Bridge-Bridge relay via Reticulum, deepening the value of "Both" mode.
- WebAuthn binding of the WebView session to a hardware security key is a Phase-9-or-later upgrade for high-assurance deployments.
- ATAK plugin integration is **NOT** planned per `meshsat/adr/0011-android-tak-via-hub-proxy.md` — TAK comes via Hub broadcast, no native TAK UI required.

## Alternatives considered

- **Pure Compose (no WebView)**: rejected — 17 settings tabs × Kotlin reimplementation = maintenance horror; Settings drifts the moment the Bridge gets a new tab.
- **Pure WebView (no Compose)**: rejected — loses native feel for the daily-use surfaces (Compose / Inbox); battery cost of holding a WebView vs native screen for hot paths is worse; native notifications + foreground service integration is messier.
- **React Native or Flutter cross-platform shell**: rejected — adds a third UI stack to a Kotlin-native + Vue-web codebase; toolchain pollution disproportionate to value.

## Compliance

- The WebView's TLS handler MUST verify SPKI pin against the stored `cert_sha256` BEFORE any request completes; mismatch = reject with operator-visible "bridge identity mismatch" error.
- Bearer JWT MUST be injected into WebView Authorization headers via an OkHttp interceptor, NOT stored in cookies / localStorage — defends against XSS exfiltration vector.
- The `paired_bridges` Room table MUST be in the migration chain (under Article XIII's single-migration-per-version rule).
- Native Compose for hot paths MUST use rememberSaveable / state-holder ViewModels per Article XIV — no XML/Fragment-retain patterns.
- The CameraX + ZXing pair-claim flow lives in native Compose (NOT WebView) — webcam access via WebView is unreliable on Android; native CameraX is the standard.
- Phase 8 closure verifies all paths end-to-end against a test Bridge in the lab; integration test (S8-15) is mandatory before release.
