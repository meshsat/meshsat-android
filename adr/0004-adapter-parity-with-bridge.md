# ADR-0004 — Adapter parity with Bridge: same conceptual interface, language-idiomatic implementations

* Status: Accepted — codified after the fact 2026-05-17. Decision predates this ADR; `docs/ARCHITECTURE.md` (2026-03-17, in the Bridge repo) is the canonical reference.
* Date: Originally decided 2026-Q1 (when Android Phases A-G began against the Go Bridge surface); recorded as ADR 2026-05-17.
* Deciders: `ufwtqkgz@meshsat.net`
* Source document: `meshsat/docs/ARCHITECTURE.md` §"Adapter Interface Parity" + §"Shared Design Patterns"

## Context

MeshSat has three peer nodes (Bridge / Android / Hub) per `docs/ARCHITECTURE.md` L5. The Bridge runs Go on Pi 5 + USB. Android runs Kotlin/Compose on a phone with BLE + SPP + cellular. Hub runs Go on a server with no serial. The same conceptual data flow — channel registry → access rules → dispatcher → per-interface workers → transform pipeline → delivery ledger — must work on all three runtimes.

Two naive approaches were rejected:

1. **Shared compiled-code module (KMP, gomobile, generated SDK)**: Adds toolchain complexity disproportionate to the actual code-sharing surface. Iridium AT command sequencing, Bluetooth SPP framing, JSPR JSON over serial — these are all platform-specific implementation details that don't share well across languages.
2. **Pure parallel implementations** (no design contract): Inevitable drift in semantics — a rule that's "implicit-deny" on Bridge and "implicit-allow" on Android is a security regression dressed as a feature gap.

## Decision

Bridge (Go) and Android (Kotlin) share **the same conceptual adapter interface**, implemented language-idiomatically per runtime. There is **no shared compiled-code symbol surface** — the contract is documented in `docs/ARCHITECTURE.md` and enforced by test parity, not by a generated SDK.

### The contract

Per `docs/ARCHITECTURE.md` §"Adapter Interface Parity":

**Bridge Gateway interface (Go):**
```go
type Gateway interface {
    Start(ctx context.Context) error
    Stop() error
    Forward(ctx context.Context, msg *transport.MeshMessage) error
    Receive() <-chan InboundMessage
    Status() GatewayStatus
    Type() string
}
```

**Android equivalent (Kotlin)** — distributed across:
- `InterfaceManager` — lifecycle state machine (Offline/Connecting/Online/Error/Disabled) with auto-reconnect backoff. Equivalent to `Gateway.Start/Stop/Status`.
- `Dispatcher.DeliveryCallback` — `suspend fun deliver(interfaceId, payload, textPreview): String?`. Equivalent to `Gateway.Forward`.
- Transport-specific classes (`MeshtasticBle`, `IridiumSpp`, `SmsSender`) — handle hardware specifics.

### The patterns (canonical list, from `docs/ARCHITECTURE.md`)

| Pattern | Bridge (Go) | Android (Kotlin) |
|---|---|---|
| Channel registry | `channel.Registry` + `ChannelDescriptor` | `ChannelRegistry` + `ChannelDescriptor` |
| Access rules | `rules.AccessEvaluator` | `rules.AccessEvaluator` |
| Dispatcher | `engine.Dispatcher` | `engine.Dispatcher` |
| Failover | `engine.FailoverResolver` | `engine.FailoverResolver` |
| Dedup | `dedup.Tracker` | `dedup.Deduplicator` |
| Rate limiting | Per-rule token bucket | `ratelimit.TokenBucket` |
| Transform pipeline | `engine.TransformPipeline` | `engine.TransformPipeline` |
| Delivery ledger | `database.MessageDelivery` | `data.MessageDeliveryEntity` |
| Interface state machine | `engine.InterfaceManager` | `engine.InterfaceManager` |
| Ed25519 identity | `routing.Identity` | `routing.Identity` |
| Dead man's switch | `engine.DeadManSwitch` | `engine.DeadManSwitch` |
| Geofence | `engine.GeofenceMonitor` | `engine.GeofenceMonitor` |
| Health scores | `engine.HealthScorer` | `engine.HealthScorer` |
| Burst queue | `engine.BurstQueue` | `engine.BurstQueue` |
| Compression | SMAZ2, llama-zip, MSVQ-SC | MSVQ-SC (ONNX encoder + Kotlin codebook decoder) |

The `ChannelDescriptor` shape — same field names, same semantics, same defaults — is identical across both platforms.

## Consequences

**Positive**
- Operators get **the same behavior contract** on the kit and on the phone. A rule with `keyword=SOS` evaluates identically on Bridge and Android.
- Engineering velocity stays high — each repo is idiomatic for its language; no foreign-language toolchain pollution.
- Cross-platform docs (the `Android vs Bridge` matrix in `README.md` L120–L149) become a 1:1 mapping rather than a "approximately like" hand-wave.
- 16 backend-parity gaps were closed atomically (MESHSAT-385 v1.5.0/v1.5.1) precisely because the contract was clear — `CreditTracker`, `mTLS for Hub`, `multi-instance TransportRegistry`, `node identity merge`, all 8 Hub commands, `credential management with PEM import`, `TCP multi-peer for Reticulum`, `YAML config export/import (Bridge-compatible)`, `announce interval + routing config UI`, `service restart`, `Reticulum dashboard widget`, `burst queue flush`, `activity log pause/resume`, `Iridium credit gauge`, `manual mailbox check`, `dashboard widget reorder` — all named, all checkable.

**Negative**
- Drift risk if either platform changes its primitive without updating the cross-platform doc. Mitigation: `docs/ARCHITECTURE.md` is the canonical reference; PRs that change a pattern must update it.
- Test parity is manual — Bridge and Android each have their own test suites. We test the **same scenarios** but not via shared test fixtures.
- The "Compression" row of the matrix is partially asymmetric — Bridge has 3 tiers (SMAZ2, llama-zip, MSVQ-SC), Android has 1 (MSVQ-SC with ONNX TX + pure-Kotlin RX). This is a deliberate consequence of mobile-platform constraints (no LLM sidecar on a phone); see ADR-0006 for rationale.

**Forward direction**
- New patterns added to one platform should be added to the other within one minor version. Document the gap explicitly in the `README.md` parity matrix until closed.
- The matrix is also the basis of Bridge's `EXECUTION-PLAN.md` Phase 5 (Android directory sync) — the Bridge defines the directory schema, Android mirrors it in Room.

## Alternatives considered

- **Kotlin Multiplatform (KMP) shared module**: rejected — adds KMP toolchain to a Go-primary ecosystem; would force `meshsat-hub` to either also become KMP-aware or to consume KMP artifacts from Maven; KMP support for Iridium AT command sequencing + serial framing is essentially zero. The patterns shared across platforms are **design-level**, not implementation-level.
- **gomobile-generated SDK**: rejected — Go-to-Android via gomobile produces opaque AARs; loses Kotlin/Coroutine idiom; ONNX Runtime Android integration requires Kotlin-native code anyway. Same compression-asymmetry argument as KMP.
- **REST-API-defined contract (Android consumes Bridge as a service)**: rejected — defeats Android's "phone IS the gateway" identity; the phone must work fully standalone.
- **Pure parallel implementations (no documented contract)**: rejected — inevitable drift in semantics; an "implicit-deny" → "implicit-allow" rule difference would be a silent security regression.

## Compliance

- Any new "pattern" added to either Bridge or Android MUST add a row to the `docs/ARCHITECTURE.md` parity matrix within the same MR/release.
- The `ChannelDescriptor` shape is the **load-bearing schema** — adding a new field requires synchronized changes in both repos before the next release.
- Drift between platforms is acceptable when **deliberate** (e.g., HeMB is bridge-only by design today, per `README.md` L132 and `meshsat/adr/0008-hemb-heterogeneous-media-bonding.md` L99–L101); drift via oversight is a bug.
- This ADR codifies the **conceptual** contract. Wire-format contracts (Reticulum, Sparkplug-B Hub-uplink, pair-protocol JWT) are codified in their own ADRs in their respective repos.
