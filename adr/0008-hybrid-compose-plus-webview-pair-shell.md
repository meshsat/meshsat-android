# 8. Hybrid Compose + WebView pair-shell

Date: 2026-05-18 (codifying future design from spec/003)

## Status

Accepted (planned)

## Context

The pair-shell (spec/003) makes Android a first-class operator surface for one or many bridges. Two ends of the spectrum:

| Option | Pros | Cons |
|---|---|---|
| **Pure Compose** | Native feel, native gestures, hardware integration | Reimplement every bridge Settings tab in Kotlin → maintenance horror |
| **Pure WebView** | Reuses bridge's Vue SPA (zero rewrite) | Kills native gestures + notifications; bad UX for hot paths |
| **Hybrid Compose + WebView** | Native for hot paths; WebView for cold paths; auto-update for cold | Two UI stacks to maintain |

## Decision

Hybrid: native Compose for hot paths (inbox, map, compose-message, people, paired-bridges list, pair-devices, hardware-talking screens); WebView wrapping the bridge's Vue SPA at `/settings` for cold paths (interfaces, rules, audit, spectrum). Same pattern as Home Assistant Companion.

WebView path enforces:
1. OkHttp `CertificatePinner` pinning to the paired bridge's SPKI (from pair-claim).
2. `Authorization: Bearer <jwt>` injected on every request via `WebViewClient.shouldInterceptRequest`.
3. On SPKI mismatch: reject load with explicit error; do NOT fall back to system trust.

## Consequences

**Positive:**
- Hot paths feel native (Compose). Cold paths auto-update with bridge releases (WebView).
- Single source of truth for Settings UI (Vue SPA on bridge); Android doesn't reimplement.

**Negative:**
- Two UI stacks. Compose ↔ WebView bridge code (JS interface) is a sharp edge.
- WebView TLS pin must be revalidated on every load; expensive but security-critical.

**Cross-reference:** spec/003-pair-shell (future).
