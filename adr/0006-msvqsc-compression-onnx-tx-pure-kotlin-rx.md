# ADR-0006 — MSVQ-SC compression: ONNX Runtime INT8 encoder for TX, pure-Kotlin codebook decoder for RX

* Status: Accepted — codified after the fact 2026-05-17. Shipped through v1.x; current `crypto/` package implementation is stable.
* Date: Originally decided 2026-Q1 (Phase A); recorded as ADR 2026-05-17.
* Deciders: `ufwtqkgz@meshsat.net`
* Source: `README.md` L172–L176 (Crypto), L429–L434 (Assets), `CLAUDE.md` L124–L131

## Context

The Bridge offers three compression tiers (SMAZ2 lossless + llama-zip LLM-lossless + MSVQ-SC lossy-semantic per `meshsat/adr/0009-three-tier-compression-model.md`). Android can run none, one, or all three. Choosing requires trading mobile-platform constraints against operator value:

- **SMAZ2**: in-process Go map lookup; pure Kotlin port is feasible but the savings on Android-side text traffic (mostly already-compressed APRS/SBD) are modest.
- **llama-zip**: requires running an LLM sidecar. Phones can't reasonably host an LLM container the way a Pi 5 can — battery, RAM, App Store size constraints all push against it. Out of scope for Android.
- **MSVQ-SC** (Multi-Stage Vector Quantization Semantic Compression): the actual differentiator. ~92% byte savings on SMS-shaped text. Requires ONNX inference for TX (sentence encoder); RX is pure codebook lookup. This is the unique-to-Android-amongst-MeshSat-peers capability (Bridge has it, but Android is where mobile SMS volume happens).

The asymmetry between TX (needs ML runtime, big) and RX (just codebook lookup, small) is the design opportunity.

## Decision

MeshSat Android implements **MSVQ-SC compression with asymmetric runtime requirements**:

### TX path — ONNX Runtime INT8 encoder
- `MsvqscEncoder` (`crypto/`) — INT8 quantized BERT-class sentence encoder running on ONNX Runtime Android (`ai.onnxruntime`).
- `SimpleWordPieceTokenizer` (`crypto/`) — minimal BERT tokenizer for the encoder's expected input format.
- Encoder output goes through **ResidualVQ quantization** to produce a sequence of codebook indices.
- Cold-start latency ~500 ms on first compression call (ONNX session initialization, lazy); subsequent calls fast.
- Asset cost: `encoder.onnx` (22 MB) + `vocab.txt` (230 KB).

### RX path — pure-Kotlin codebook decoder, NO ML runtime
- `MsvqscCodebook` (`crypto/`) — decodes a quantized index sequence to text via codebook lookup + nearest-neighbor corpus reconstruction.
- **NO** ONNX Runtime dependency on the RX path — pure Kotlin.
- Asset cost (RX-only): `codebook_v1.bin` (12 MB) + `corpus_index.bin` (70 KB).

### Wire format
- `MsvqscWire` (`crypto/`) — header discriminator distinguishes MSVQ-SC payloads from plain text.
- Composes inside `TransformPipeline`: `text → MSVQ-SC encode (optional) → AES-GCM encrypt (optional) → base64 → SMS/SBD`.
- **Auto-detection on RX** works for all combinations (encrypted+compressed, either alone, plain).

## Consequences

**Positive**
- Operators on cellular SMS save ~92% of their character budget — economic AND latency win.
- RX-only consumers (read-only third-party tools, or a phone with very tight storage where the operator wants to *receive* MSVQ-SC payloads but never send them) can ship with the 12 MB codebook and skip the 22 MB encoder.
- ONNX Runtime cold-start is bounded — only on first call, not per-message. Operator pre-warms by composing a test message at app start.
- Pure-Kotlin RX path means the RX assets are robust to any ONNX Runtime version-incompatibility issues — encoder version changes don't break the decoder.

**Negative**
- Total asset cost ~35 MB (sum of `encoder.onnx` + `codebook_v1.bin` + `corpus_index.bin` + `vocab.txt`). Material for app-size; operator who never composes SMS could in principle skip the encoder asset, but the v2.8.6 release ships everything for simplicity.
- ONNX Runtime CPU-only on Android — encoder latency depends on phone CPU. Acceptable on modern phones (Pixel 9a sub-second compression on warm cache), borderline on entry-level phones.
- The codebook is **versioned** (`codebook_v1.bin`) — a future `codebook_v2.bin` requires an opt-in upgrade path with grace period (sender + receiver must agree on codebook version, similar to the Bridge's pair-protocol versioning model).
- Asymmetric design means an Android-only-RX-no-TX configuration is *possible* (RX-only assets), but not currently exposed as a build variant. Future option.

**Forward direction**
- The encoder could be quantized further (INT4 or INT2) at the cost of compression quality — out of scope for v1.
- A "compose-message" pre-warm hook at app start could eliminate the operator-facing cold-start latency entirely.
- Per-deployment codebook training (like Bridge's roadmap in `meshsat/adr/0009-three-tier-compression-model.md`) could land on Android too once the workflow is proven on Bridge.

## Alternatives considered

- **No MSVQ-SC on Android (lossless-only)**: rejected — loses the SMS character-budget win that's most valuable specifically on mobile.
- **Run a llama-zip-class LLM in-process on Android**: rejected — neither battery nor RAM budget supports it; even if it could, the ~200 ms latency budget the Bridge accepts for llama-zip is much harsher when the operator is composing interactively on a phone.
- **Server-side MSVQ-SC (Android calls Hub for encoding)**: rejected — defeats offline-first (Article IX); operator without internet has no compression.
- **Symmetric ONNX TX + ONNX RX**: rejected — RX-only consumers would carry the 22 MB encoder for no reason; the pure-Kotlin RX path is strict-better for RX-only deployment shape.

## Compliance

- The encoder MUST run on the TX path only — `MsvqscEncoder` never invoked on RX path.
- The codebook decoder MUST work without ONNX Runtime — `MsvqscCodebook` MUST NOT take `ai.onnxruntime` as a compile-time dependency.
- Wire-format additions to `MsvqscWire` are BREAKING for active deployments — bump the header discriminator and ship both old + new decoders for one minor release.
- ProGuard / R8 keep rule `-keep class ai.onnxruntime.** { *; }` MUST stay in `proguard-rules.pro` (Constitution Article VII) — JNI bindings + reflection.
- Asset assertions in `MsvqscEncoder.init()` MUST validate hash of `encoder.onnx` matches expected before first use — protects against accidental asset corruption on sideload.
