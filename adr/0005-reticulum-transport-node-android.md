# ADR-0005 — MeshSat Android is a full Reticulum Transport Node (10+ interfaces, not just a client)

* Status: Accepted — codified after the fact 2026-05-17. Shipped 2026-Q1 (MESHSAT-199 + MESHSAT-208 through MESHSAT-272). Cross-component validation against stock Python `rns` is part of the test surface.
* Date: Originally decided 2026-Q1; recorded as ADR 2026-05-17.
* Deciders: `ufwtqkgz@meshsat.net`
* Source: `CLAUDE.md` L310–L331, `README.md` L150–L160, mirror of `meshsat/adr/0007-reticulum-routing-layer.md`

## Context

The Bridge repo committed to Reticulum-compatible routing with 9 cross-connected interfaces (`meshsat/adr/0007-reticulum-routing-layer.md`). Android could have chosen either to be a **routing client** (consumes the Bridge as its Reticulum gateway, never relays itself) or a **first-class Transport Node** (full announce relay, cross-interface forwarding, path discovery — same role as Bridge).

The "phone IS the gateway" identity (Article I, `README.md` L1–L8) demands the second option. A field operator with no Bridge in range must still get cross-interface routing — Meshtastic-BLE ↔ Iridium-SPP ↔ SMS ↔ MQTT, all on the phone alone.

## Decision

**MeshSat Android is a full Reticulum Transport Node — not a client.** It implements:

### 10+ Reticulum interfaces (matches and exceeds Bridge's 9)
- `RnsMeshtasticBleInterface` — Meshtastic LoRa over BLE
- `RnsIridiumInterface` — Iridium 9603N SBD over HC-05 SPP
- `RnsIridium9704Interface` — Iridium 9704 IMT over HC-05 SPP (MESHSAT-272)
- `RnsSmsInterface` — Native Android SMS
- `RnsMeshInterface` — Mesh logical interface
- `RnsMqttInterface` — Eclipse Paho client interface
- `RnsAprsInterface` — APRS via KISS TNC + APRS-IS
- `RnsTcpInterface` (HDLC) — TCP/HDLC for stock-RNS interop (MESHSAT-268)
- `RnsBlePeripheralInterface` (GATT server) — BLE peripheral (MESHSAT-269)
- `RnsTorInterface` — Tor SOCKS5 proxy (MESHSAT-270)
- `RnsWireGuardInterface` — WireGuard tunnel TCP (MESHSAT-271)

### Transport Node behavior
- `RnsTransportNode` — central router with cross-interface relay; announces itself with `CAP_TRANSPORT_NODE` flag set; handles path request/response; relays announces with hop counting + dedup.
- `RnsForwardingTable` — cost-aware routing across all registered interfaces; paid bearers (cost > 0) default to non-floodable to prevent PathFinder from burning operator money chasing unknown destinations.
- `RnsPathTable` — destination → next-hop with TTL; learned from announce relay.
- `RnsLinkManager` — 3-packet ECDH handshake → AES-256-GCM encrypted link with forward secrecy per session.
- `RnsLink` — link state machine (Pending → Established → Closed), 18s keepalive heartbeat, 60s timeout detection (`LinkKeepalive`).
- `RnsResource` — chunked reliable transfer with bitmap + SHA-256 verify (mirrors Bridge's `routing.ResourceTransfer`).
- `RnsAnnounce` / `RnsAnnounceHandler` — wire-compatible with Reticulum spec (validated against Python `rns` reference).

### Wired into `GatewayService` lifecycle
The Transport Node starts when `GatewayService` initializes (MESHSAT-267) and shuts down on service stop. Interfaces register at startup and bind to their underlying transports through the same `InterfaceManager` state machine that's used for non-Reticulum delivery.

## Consequences

**Positive**
- Operators standing in the field with **only** their phone get cross-interface routing — Meshtastic → SMS → APRS works without a Bridge in range.
- Wire-compatible with stock Python `rns` — an existing Reticulum mesh can include MeshSat Android nodes without modification (validated MESHSAT-208/267-272).
- The 10+ interfaces include channels Bridge **doesn't have on Pi 5** (BLE peripheral GATT server, Tor SOCKS5, WireGuard tunnel) — Android contributes capabilities to the mesh, not just consumes from it.
- The Transport Node's cost-aware forwarding makes the phone behave responsibly with the operator's wallet — free bearers exhaust before paid.
- Constitution Article XI is the load-bearing rule: any wire-format change requires explicit ADR + interop test against stock RNS.

**Negative**
- 24 source files (~6,100 lines) in `reticulum/` package — significant maintenance surface. Mitigation: heavy unit test coverage, especially for wire format (HKDF, packet flags, HDLC framing).
- Battery cost — Transport Node is always relaying, even when the operator isn't actively using the phone. Mitigation: per-interface enable/disable in Settings; Battery-Optimization unrestricted recommended (per `README.md` L331–L334).
- Phone hardware constraints — no compute GPU for FEC-heavy bonded paths means HeMB-class workloads remain Bridge-only for now (consistent with `README.md` L132).
- Tile-provider thread fragility (osmdroid) and BouncyCastle/Conscrypt provider ordering (MESHSAT-497) constitute the bulk of Android-specific Reticulum gotchas — both have dedicated Articles in the constitution.

**Forward direction**
- BLE Mesh Reticulum interface evolution is plausible if Android Bluetooth Mesh stack matures; today's `RnsBlePeripheralInterface` is GATT-server-based.
- The "10+ interfaces" claim leaves room for future additions without re-numbering or re-baselining — adding an 11th interface is a code addition, not a wire change.

## Alternatives considered

- **Android-as-RNS-client only** (consume Bridge as the only Transport Node): rejected — defeats "phone IS the gateway" identity (Article I). Operator with no Bridge in range would lose cross-interface routing.
- **Skip Reticulum entirely; use a custom per-platform routing protocol**: rejected — re-invents identity + addressing + announce + link semantics; loses stock-RNS interop; violates cross-platform parity (Bridge already chose Reticulum per `meshsat/adr/0007-reticulum-routing-layer.md`).
- **Light-weight "edge" RNS subset**: rejected — operationally similar to client mode; loses the operator-side cost-aware forwarding benefit.

## Compliance

- New RNS interfaces MUST extend the same base class used by the existing 10+ (so they integrate with `RnsTransportNode`'s registry and `RnsForwardingTable`).
- Paid bearers (cost > 0) MUST default `floodable=false` — `RnsSmsInterface`, `RnsIridiumInterface`, `RnsIridium9704Interface` enforce this.
- Constitution Article XI is binding: any wire-format change requires explicit ADR + interop test against stock Python `rns`.
- The `RnsTransportNode.kt` file is the single integration point with `GatewayService` — bringing up a new interface goes through that, NOT direct-wiring.
- This ADR is the Android mirror of `meshsat/adr/0007-reticulum-routing-layer.md`. Drift between these two ADRs is a process bug; cross-link before merge.
