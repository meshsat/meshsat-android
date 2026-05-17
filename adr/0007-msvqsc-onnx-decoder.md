# 7. MSVQ-SC decoder is pure-Kotlin; encoder uses ONNX Runtime

Date: 2026-05-18 (codifying shipped design)

## Status

Accepted

## Context

MSVQ-SC (Multi-Stage Vector Quantization Semantic Compression) is the lossy semantic compression layer for severely bandwidth-constrained transports (Iridium SBD, Meshtastic). CGC-verified files:

- `crypto/MsvqscCodebook.kt` — codebook lookup tables
- `crypto/MsvqscEncoder.kt` — encode path
- `crypto/MsvqscWire.kt` — wire-format serialisation
- `crypto/SimpleWordPieceTokenizer.kt` — input tokeniser

The encoder uses an ONNX Runtime backend for the actual neural-net vector quantization. The decoder is pure-Kotlin (no ML runtime needed for decode).

## Decision

Keep the encoder dependent on ONNX Runtime (`onnxruntime-mobile`). Keep the decoder pure-Kotlin (no `onnxruntime`, no JNI). Reason: the decoder runs on every received message; pure-Kotlin keeps it fast + auditable + battery-friendly. The encoder runs only on user-typed outgoing messages, where ONNX cost is acceptable.

## Consequences

**Positive:**
- Receive path has zero ML-runtime overhead (no JNI cost per inbound message).
- Decoder is auditable in pure Kotlin.
- Sender path gets best compression via ONNX-accelerated VQ.

**Negative:**
- Two implementations to keep in sync (encoder + decoder). Mitigated by codebook + wire-format test fixtures.

**Cross-references:** `crypto/MsvqscEncoder.kt`, `crypto/MsvqscWire.kt`, `crypto/MsvqscCodebook.kt`.
