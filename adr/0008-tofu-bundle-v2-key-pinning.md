# ADR-0008 — TOFU + Bundle v2 key pinning: signed pubkey embedded in bundle, pinned in Room on first import

* Status: Accepted — codified after the fact 2026-05-17. Shipped v2.8.5 (MESHSAT-495).
* Date: 2026-04 (decision + implementation); 2026-05-17 (ADR recorded).
* Deciders: `ufwtqkgz@meshsat.net`
* Source: `README.md` L183–L194, `CLAUDE.md` L90 (recent release history v2.8.5)

## Context

MeshSat Android consumes QR key bundles (`meshsat://key/...`) from the Bridge to import AES-256-GCM per-channel encryption keys. The v1 bundle format (legacy) was unsigned in any pubkey-pinning sense — an attacker who could intercept the QR display (or render a counterfeit QR) could feed Android a malicious bundle that decrypted to attacker-controlled keys for "the Bridge."

Industry precedent for this attack class is well-documented (Syncthing Device ID pinning, Matrix MSC4108 verification, WhatsApp / Signal "safety number changed" UX). The defense is **Trust On First Use (TOFU)** with cryptographic verification of subsequent encounters.

## Decision

**Bundle v2 format** (shipped v2.8.5) embeds the 32-byte Ed25519 signing pubkey inside the signed payload. **TOFU pinning** verifies signatures against the *stored* pubkey on subsequent imports.

### Bundle v2 binary format
```
Version(1)=0x02 | BridgeHash(16) | Timestamp(4) | EntryCount(1) | SigningPubkey(32) | Signature(64) | Entries...
```

The signature covers all bytes **except** the 64 signature bytes themselves (Version + BridgeHash + Timestamp + EntryCount + SigningPubkey + Entries). The pubkey is therefore inside the signed envelope — it **cannot be swapped without invalidating the signature**.

### TOFU state machine
- **First import** from a new bridge hash → pubkey is pinned in the `bridge_trust` Room table (added in migration 12→13). `ImportResult = NEW_TRUSTED`.
- **Subsequent imports** → signature verified against the *stored* pubkey. `ImportResult = EXISTING_TRUSTED` on match.
- **Mismatch** (key rotation OR impersonation) → `ImportResult = KeyMismatch`. Import is **rejected**. User sees an explicit warning + re-pin option (modeled on Signal "safety number changed" — blocking, not a toast).
- **Bundle v1 (legacy)** still imports for backward compat with older bridges. Flagged `ImportResult = UNVERIFIED_V1` with a user-visible warning so operator knows the import is not pubkey-verified.

### `KeyBundleImporter.kt` design notes
- Takes DAO interfaces directly (DI-friendly for testing).
- Uses `java.util.Base64` (NOT `android.util.Base64`) for JVM test compatibility — Constitution Article VI.
- Returns sealed `ImportResult` class — exhaustive `when` in callers; future variants don't silently fall through.
- 11 unit tests cover every TOFU path (Success / KeyMismatch / InvalidSignature / Malformed / Unverified-v1).

### Cross-repo follow-up
- **Bridge MUST emit Bundle v2** for new bridge installs. Tracked as MESHSAT-500 (cross-repo, open against meshsat). Until that lands, fielded bridges may continue emitting v1 and operators see `UNVERIFIED_V1` warnings — acceptable for the grace period.

## Consequences

**Positive**
- A counterfeit QR with an attacker-controlled signing pubkey cannot impersonate a previously-paired bridge — the signature against the stored pubkey fails.
- The operator gets an explicit, blocking UX when a bridge's key actually rotates (deliberate rekey OR impersonation attempt) — they must consciously re-pin.
- The sealed `ImportResult` makes the contract explicit at every caller — no silent fall-through.
- Pure unit-testable with 11 covering tests — TOFU correctness is verifiable without hardware.

**Negative**
- Bridge-side v2 emission is a separate change (MESHSAT-500) — until then, mixed v1/v2 deployments are operational reality.
- The `bridge_trust` table is a new schema migration (12→13) — must obey Article XIII (single Room migration per version).
- "Re-pin after legitimate rotation" UX needs operator training; an operator who doesn't understand the prompt could either re-pin a malicious bundle ("just say yes") OR reject a legitimate rotation ("scary popup, abort"). Mitigation: copy on the prompt explicitly references the bridge hash + the fingerprint of both old and new pubkey, so the operator can verify out-of-band.

**Forward direction**
- The TOFU model could extend to cross-bridge identity (Android trusts that bridges A and B are *different bridges*, prevents an attacker from substituting bridge A's identity for bridge B). Not in v2.8.5 scope; tracked as future work.
- Key rotation flow could include a Bridge-side broadcast that pre-warns paired devices ("Bridge X is rotating its signing key on $DATE — expect a re-pin prompt"). Out of scope for v2.8.5.

## Alternatives considered

- **No pinning (accept all bundles unconditionally)**: rejected — the original v1 model; vulnerable to QR substitution.
- **Out-of-band fingerprint verification only** (no embedded pubkey): rejected — requires operator to manually compare hex strings every time, unrealistic in field UX. The signed bundle is the on-bundle commitment.
- **Hardware-backed root of trust** (each Bridge has a manufacturer-attested cert): rejected — adds a manufacturing process step that MeshSat doesn't have; TOFU is the right shape for a self-hosted ecosystem.
- **Hub-mediated verification** (Android asks Hub "is this bridge legit?"): rejected — defeats offline-first (Article IX); operator without internet can't pair.

## Compliance

- New bundle entries (e.g., a new channel type) MUST be appended to the v2 entry format without changing the header — backward-compatible additions.
- A `bundle v3` (e.g., if X25519-Diffie-Hellman session-derived keys become preferred over AES per-channel) requires BUMPING the Version byte and shipping both decoders for a grace period.
- The `bridge_trust` table's pubkey column MUST remain the source-of-truth for verification — no in-memory caches that could mask a mismatch.
- The `KeyBundleImporter` MUST keep using `java.util.Base64` and DAO interfaces directly (Constitution Article VI + DI-friendliness).
- Cross-repo coordination: when MESHSAT-500 lands on the Bridge side, an integration test pairing v2.8.5+ Android against a v2-emitting Bridge MUST be added to the Bridge's `test/integration/` suite.
