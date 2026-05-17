# 4. BouncyCastle via `addProvider`, NEVER `insertProviderAt`

Date: 2026-05-18 (codifying decision originally made early 2026)

## Status

Accepted

## Context

CGC-verified: `crypto/KeyBundleImporter.kt` explicitly passes `"BC"` to `Signature.getInstance("Ed25519", "BC")`. Adding BC via `insertProviderAt(BC, 1)` would displace Android's built-in providers used by OkHttp + WorkManager + AndroidX Security, silently breaking TLS verification elsewhere in the app.

## Decision

Use `Security.addProvider(BouncyCastleProvider())` (appends to provider list). Explicit `"BC"` provider name in `getInstance` calls so we don't accidentally pick a sibling provider. NEVER `insertProviderAt(BC, 1)`.

## Consequences

**Positive:**
- TLS via OkHttp uses Android's default provider chain unchanged.
- Crypto routines that explicitly want BC get BC; everything else uses Android defaults.

**Negative:**
- Slight per-call lookup cost — negligible.

**Enforced by:** Component Article C-II + code review.
