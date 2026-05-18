# Changelog — meshsat-android

All notable changes to this project. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) + [Conventional Commits](https://www.conventionalcommits.org/).

Generated from git history + tags by `scripts/sdd-generate-changelog.py` on 2026-05-18.


## [v2.8.6] - 2026-04-05

### Added

- local release telemetry ring buffer [MESHSAT-494] (b56a6856)

### Changed

- drop radio-config + audit-log screenshots, keep hero only [MESHSAT-496] (4335e794)
- remove features-menu screenshot (didn't render well) [MESHSAT-496] (6cec8fd2)
- comprehensive rewrite + 6 screenshots [MESHSAT-496] (7d908b93)
- comprehensive README overhaul + GitHub metadata script [MESHSAT-496] (cf7798ed)


## [v2.8.5] - 2026-04-05

### Added

- TOFU key pinning in KeyBundleImporter + bundle v2 [MESHSAT-495] (7bfce9d4)


## [v2.8.4] - 2026-04-05

### Changed

- bump version to v2.8.4 (d9539b1f)

### Fixed

- gate all I/O on connection state, silence disconnected-state spam [MESHSAT-499] (2d70a61e)


## [v2.8.3] - 2026-04-05

### Fixed

- correct ms/sec unit mismatch in PassScheduler OOM [MESHSAT-498] (2191f7b9)
- cache pass predictions to prevent OOM in PassScheduler [MESHSAT-498] (c0036113)


## [v2.8.2] - 2026-04-05

### Fixed

- move BouncyCastle to end of JCA provider chain [MESHSAT-497] (d0ed2571)


## [v2.8.1] - 2026-04-05

### Changed

- bump version to v2.8.1 (12b8e1df)

### Fixed

- add osmdroid keep rules to prevent white map tiles on release [MESHSAT-493] (300dc605)


## [v2.8.0] - 2026-04-05

### Added

- add release signing with OpenBao + Play Store AAB (fc2d4201)

### Changed

- bump version to v2.8.0 (03cae60e)

### Fixed

- add dontwarn rules for Tink compile-time annotations (04d58c5d)


## [v2.7.0] - 2026-04-04

### Added

- receive TAK positions from Hub broadcast + settings API [MESHSAT-448] (e28c3c44)
- TAK/CoT-compliant Canvas markers — diamond, square, emergency [MESHSAT-464] (67679b84)
- protobuf v1 + waypoint builder + enriched position [MESHSAT-456] (5060c443)
- add TAK Settings section with enable/callsign/output toggles [MESHSAT-451] (98b8644c)
- auto-forward + /api/sms/auto-forward endpoint [MESHSAT-447] (e7f30778)
- add /api/sms/send to Android LocalApiServer [MESHSAT-447] (421629ff)
- Android outbound SMAZ2 compress + wildcard key encrypt [MESHSAT-447] (6e5dfed9)
- port SMAZ2 compressor/decompressor to Kotlin [MESHSAT-447] (9c256f61)

### Changed

- bump version to v2.7.0 (61d6e36c)

### Fixed

- replace zoomToBoundingBox with direct center+zoom [MESHSAT-479] (7718d820)
- debounce Room position Flow to prevent ANR from marker burst [MESHSAT-479] (1fceda50)
- prevent ANR from repeated zoomToBoundingBox on marker updates [MESHSAT-479] (e9f7ed47)
- subscribe to TAK broadcast topic for fleet-wide positions [MESHSAT-448] (7557d701)
- use CotPrecision camelCase field names (altSrc, geoPointSrc) [MESHSAT-456] (5b467aaf)
- use explicit getters for PrecisionLocation proto fields [MESHSAT-456] (028059ea)
- use java_package imports for proto-generated classes [MESHSAT-456] (7635e8c3)
- correct protobuf class names for java_multiple_files + proto3 [MESHSAT-456] (4a0e7f3c)
- use explicit hasXxx() checks in protoToCotEvent [MESHSAT-456] (4e9f3873)
- show SmsReceiver-decrypted text in Messages UI [MESHSAT-447] (9585794c)
- fall back to Hub wildcard key (sms:*) for SMS decryption [MESHSAT-447] (a142d46c)
- synchronize RLNC decoder to prevent concurrent ArrayIndexOutOfBounds [MESHSAT-447] (03a93c0b)
- wire HeMB inbound detection on Android RnsTransportNode [MESHSAT-443] (0a1d499e)


## [v2.6.1] - 2026-03-31

### Changed

- bump version to v2.6.1 (38c103cb)

### Fixed

- backport 2 HeMB bug fixes from bridge E2E integration [MESHSAT-415] (e2022e13)


## [v2.6.0] - 2026-03-29

### Added

- port HeMB bearer bonding protocol to Android [MESHSAT-431] (b752c2da)

### Changed

- bump version to v2.6.0 (c10fa781)
- rewrite README to match v2.5.0 feature set [MESHSAT-354] (dd59de14)


## [v2.5.0] - 2026-03-29

### Added

- TLS+mTLS for TCP interface, BouncyCastle for Android 16 [MESHSAT-354] (39c80ae2)

### Changed

- bump version to v2.5.0 (4c9edc48)


## [v2.4.1] - 2026-03-29

### Fixed

- ping button uses HubReporter client, not MqttTransport (3ba47c1a)


## [v2.4.0] - 2026-03-29

### Fixed

- mTLS trust store must include system CAs alongside custom CA (17a2a855)


## [v2.3.1] - 2026-03-29

### Added

- ECDSA-P256 signed birth messages for Hub verification (424ec3b9)


## [v2.3.0] - 2026-03-29

### Added

- health LED, ping button, QR auto-populate fix (aef67178)


## [v2.2.2] - 2026-03-28

### Fixed

- support both inline and nonce QR formats (29326ea9)


## [v2.2.1] - 2026-03-28

### Added

- two-step QR provisioning (nonce URL + HTTPS claim) (e1cd3ad6)


## [v2.2.0] - 2026-03-28

### Added

- QR code Hub provisioning with mTLS auto-config (8b175baa)


## [v2.1.0] - 2026-03-28

### Added

- wire MQTT Reticulum interface for Hub interop [MESHSAT-354] (b401254d)


## [v2.0.1] - 2026-03-28

### Added

- add Cellular NITZ as stratum 0 time source [MESHSAT-410] (005c1731)

### Fixed

- paid interface filter + bridge-compatible wire formats [MESHSAT-414] (31b05a97)


## [v2.0.0] - 2026-03-28

### Added

- DTN + FEC + Time Sync + RLNC protocol enhancements [MESHSAT-407] [MESHSAT-408] [MESHSAT-409] [MESHSAT-410] [MESHSAT-411] (b3e14bac)


## [v1.9.1] - 2026-03-28

### Fixed

- use online OSM tiles — MBTiles offline never rendered (413f904f)


## [v1.9.0] - 2026-03-28

### Fixed

- use osmdroid auto-detect for MBTiles + MapScreen outside NavHost (197fa660)


## [v1.8.5] - 2026-03-28

### Fixed

- move MapScreen outside NavHost to prevent view detachment (44724ebe)


## [v1.8.4] - 2026-03-27

### Fixed

- restart tile provider threads after view re-attach (995666ab)


## [v1.8.3] - 2026-03-27

### Fixed

- singleton MapView to survive Compose NavHost tab switches (8e99535f)


## [v1.8.2] - 2026-03-27

### Fixed

- proper osmdroid lifecycle management for tile persistence (891b7a4e)


## [v1.8.1] - 2026-03-27

### Fixed

- tile provider persistence and dark mode color inversion (885900ba)


## [v1.8.0] - 2026-03-27

### Added

- replace Leaflet/WebView with osmdroid native map rendering (a76504fa)


## [v1.7.1] - 2026-03-27

### Added

- vector tile rendering, offline-first, bundled world map [MESHSAT-402] (812c516e)
- vector tile rendering with dark/light themes and offline-first default (b5eda0ad)

### Changed

- bump version to v1.7.1 (306ecf52)


## [v1.6.0] - 2026-03-27

### Added

- offline MBTiles map tile support (db278060)

### Changed

- bump version to v1.6.0 (99761d06)


## [v1.5.5] - 2026-03-27

### Changed

- bump version to v1.5.5 (053ca6d0)

### Fixed

- nuclear reset for corrupted EncryptedSharedPreferences (9ebbb7b5)


## [v1.5.4] - 2026-03-27

### Fixed

- load tiles via WebViewAssetLoader instead of file:// origin (a66a2078)


## [v1.5.3] - 2026-03-27

### Changed

- bump version to v1.5.3 (2ba96366)

### Fixed

- handle corrupted EncryptedSharedPreferences on startup (7c0bf59b)


## [v1.5.2] - 2026-03-27

### Changed

- bump version to v1.5.2 (dfbacfb3)

### Fixed

- foreground service crash on Android 14+ when BT permission denied (aff681f2)


## [v1.5.1] - 2026-03-27

### Added

- dashboard widget reorder dialog with persistent order [MESHSAT-401] (73cc818c)

### Changed

- bump version to v1.5.1 (95897f60)


## [v1.5.0] - 2026-03-27

### Added

- Iridium credit tracking and gauge visualization [MESHSAT-399] (655c7fd0)
- Reticulum widget and dashboard enhancements [MESHSAT-396] [MESHSAT-397] [MESHSAT-398] [MESHSAT-400] (fa736a0c)
- credential management screen with PEM import [MESHSAT-391] (34944471)
- wire pass scheduler, mTLS, registry, and TCP peers [MESHSAT-386] [MESHSAT-387] [MESHSAT-388] [MESHSAT-392] (d66735ea)
- announce interval and routing config UI [MESHSAT-394] (3a9fb369)
- YAML config export/import matching Bridge format [MESHSAT-393] (2965d991)
- TCP multi-peer management with Room DB [MESHSAT-392] (590ccb24)
- multi-instance transport registry [MESHSAT-388] (8a1e5875)
- mTLS client certificate support for Hub MQTT/NATS [MESHSAT-387] (01b555bb)
- pass-aware scheduling — 4-mode transport polling [MESHSAT-386] (9e4c1b08)
- add service restart API and UI button [MESHSAT-395] (ac51ef32)
- implement send_mt, flush_burst, config_update, reboot commands [MESHSAT-390] (241b8522)
- preserve OTA-learned node identity on config download merge [MESHSAT-389] (35edb689)
- credential import via Hub sync + QR bundles [MESHSAT-369] (a00b58e0)

### Changed

- bump version to v1.5.0 (60341fb3)


## [v1.4.0] - 2026-03-23

### Added

- add TCP_HDLC encapsulation enum for stock RNS wire compat [MESHSAT-199] (216fee10)
- HubReporter — bridge-to-hub uplink protocol for Android [MESHSAT-292] (81f6cafa)
- add RnsAstrocastInterface + GatewayService integration [MESHSAT-12] (2e234c9f)
- add TCP, BLE peripheral, Tor, and WireGuard interfaces [MESHSAT-268/269/270/271] (dc52780b)
- wire RnsTransportNode into GatewayService lifecycle [MESHSAT-267] (be291f9d)
- add RnsIridium9704Interface for Reticulum over IMT [MESHSAT-272] (a023cf27)
- add Transport Node with forwarding table and packet relay [MESHSAT-199] (dbd8c26d)
- integrate Iridium 9704 transport into GatewayService [MESHSAT-245] (0a6c622a)
- add RockBLOCK 9704 IMT transport via Bluetooth SPP [MESHSAT-245] (cfb61904)
- add Meshtastic radio config tab with all settings [MESHSAT-243] (7a0ed6d0)
- adopt official Meshtastic protobuf bindings [MESHSAT-241] (ba6ebfc9)

### Changed

- bump version to v1.4.0 (83f50817)
- add RnsIridium9704Interface unit tests [MESHSAT-272] (a6dc75bf)


## [v1.3.4] - 2026-03-20

### Changed

- bump version to v1.3.4 (8215041e)

### Fixed

- start service after permissions + bundle Leaflet locally (003d70c8)


## [v1.3.3] - 2026-03-20

### Changed

- bump version to v1.3.3 (8bd12092)

### Fixed

- crash on start + map always visible (cf1b4174)


## [v1.3.2] - 2026-03-20

### Changed

- bump version to v1.3.2 (c6b2848b)

### Fixed

- crash after 1min + no cell tower location (a20e2e7f)


## [v1.3.1] - 2026-03-20

### Changed

- bump version to v1.3.1 (61019e45)

### Fixed

- maps not loading — WebView needs non-null base URL for external resources (8b2b3257)


## [v1.3.0] - 2026-03-20

### Added

- add message fragmentation support — parity with Bridge [MESHSAT-234] (26c88002)
- wire directed messaging with ACK/REJ into GatewayService [MESHSAT-232] (7cd9eb9f)
- directed messaging with ACK/REJ tracking [MESHSAT-232] (f880ff8e)
- add smart position beaconing with corner pegging [MESHSAT-231] (d79a010b)
- add smart position beaconing with corner pegging [MESHSAT-231] (fe093482)
- APRS-IS direct connect — standalone APRS station without APRSDroid [MESHSAT-230] (5dee4142)
- Astronode S serial protocol driver + SPP transport [MESHSAT-12] (15c12e32)
- CoT v2.0 generation + ATAK integration + inbound CoT parsing [MESHSAT-191] (5d441c3b)
- QR code key sync with Hub, AES-GCM wire format tests [MESHSAT-205] (1666cf20)
- per-channel compression toggle — disable MSVQ-SC for Iridium [MESHSAT-203] (f7d51bfd)
- bidirectional SMS relay between Android and Hub via MQTT [MESHSAT-196] (6e5d6251)
- path discovery with cost-aware routing, reliable resource transfer [MESHSAT-222] [MESHSAT-223] (ed9792fb)
- 5 transport interfaces — BLE, SMS, Iridium, APRS, MQTT [MESHSAT-213] [MESHSAT-215] [MESHSAT-216] [MESHSAT-218] [MESHSAT-220] (f37804be)
- Meshtastic BLE interface adapter for Reticulum packets [MESHSAT-213] (c1590152)
- ECDH link handshake with HKDF key derivation [MESHSAT-211] (95e07865)
- wire-compatible announce with 64-byte public_key field [MESHSAT-209] (2c2f4219)
- wire-compatible packet format, destination hash, announce data [MESHSAT-208] (36dcb8d3)
- protocol version byte, SBD fragmentation, cert pinning, APRS UI [MESHSAT-190] [MESHSAT-192] [MESHSAT-195] (e74457db)
- Android APRS adapter — KISS codec, AX.25, APRS encode/decode [MESHSAT-138] (609d0525)
- Android MQTT client for Hub connectivity [MESHSAT-137] (244ba2d4)

### Changed

- bump version to v1.3.0 (318a110b)

### Fixed

- align CRC byte order with Go bridge + cross-impl test vectors [MESHSAT-233] (9d750be1)

### Security

- hardware-backed Android Keystore for all crypto keys [MESHSAT-194] (de9b043a)


## [v1.2.1] - 2026-03-16

### Fixed

- lazy SMS permission request with banner in Messages screen (e85149b0)


## [v1.2.0] - 2026-03-16

### Added

- Peers screen, message tabs, map layers, status bar, topology stats, interface tabs [MESHSAT-74] [MESHSAT-75] (31a82de2)
- dashboard overhaul + pass elevation chart [MESHSAT-72] [MESHSAT-73] (649d43fa)


## [v1.1.1] - 2026-03-16

### Fixed

- maps tiles, SMS permissions, navigation redesign (e04b0574)


## [v1.1.0] - 2026-03-16

### Added

- Phase J satellite pass predictor [MESHSAT-69] (d3ad450d)
- add radio config + device admin screen [MESHSAT-68] (dc3745d1)
- Phase I interface management screen [MESHSAT-67] (092265a1)
- Phase H bridge rules management UI [MESHSAT-66] (25df6bda)

### Fixed

- audit log screen (Android), battery/lifecycle, dynamic interfaces, reboot confirm [MESHSAT-70] [MESHSAT-71] (5319f20a)


## [v1.0.2] - 2026-03-16

### Fixed

- maps grey square, invasive SMS perms, add dark/light theme toggle (3e679878)


## [v1.0.1] - 2026-03-16

### Fixed

- crash on service creation — defensive startForeground + try/catch init (76ef385b)


## [v1.0.0] - 2026-03-16

### Added

- close MESHSAT-57 parity gaps — dedup wiring, telemetry, UI fixes, 59 unit tests [MESHSAT-57] (3606ebc2)
- Phase G UI parity — settings, topology, deliveries, geofence [MESHSAT-65] (64a31b7f)
- Phase F config, signing, and local REST API [MESHSAT-64] (c68b8c92)
- Phase E routing layer — identity, announce, link manager [MESHSAT-63] (8a0454b5)
- Phase D field intelligence — deadman, geofence, health, codecs, burst [MESHSAT-62] (c0b93c88)
- Phase C transport hardening — state machine, store-forward, QoS [MESHSAT-61] (6e2743a7)
- port Go gateway routing to Kotlin — Phase B [MESHSAT-60] (93ccf790)
- add core infrastructure — channel registry, dedup, rate limiter, transform pipeline [MESHSAT-59] (ab9679b4)
- add MSVQ-SC compression settings UI [MESHSAT-28] (78e77489)
- MSVQ-SC lossy semantic compression for SMS [MESHSAT-28] (36812fa1)

### Changed

- README rewrite, issue templates, contributing guide [MESHSAT-57] (1572a03c)
- add pipeline for APK build + GitLab/GitHub releases [CUBEOS-72] (aebd8549)

### Fixed

- audit fixes — ProGuard rules, tensor leak, thread safety, NaN guard (678b44f1)
- use bash to run gradlew (chmod fails on read-only checkout) (c41dfdae)


## [v0.3.0] - 2026-03-11

### Added

- chat replies, per-conv encryption, GPS map, device info, notifications [CUBEOS-72] (2dd4db66)

### Fixed

- SMS detection, per-conversation keys, signal graphs [CUBEOS-72] (265f512f)


## [v0.2.0] - 2026-03-11

### Added

- conversations, map, SOS, signal graphs, bug fixes [CUBEOS-72] (117f4316)
- register as default SMS app candidate for GrapheneOS [CUBEOS-70] (000e5005)

### Fixed

- keep Rules tab, move Crypto to Settings, remove duplicate screens [CUBEOS-72] (64999dfd)
- BLE scan, SMS decrypt logging, message UX improvements [CUBEOS-72] (de2bccf9)


## [v0.1.0] - 2026-03-11

_No commits within window._


## [v0.1.1] - 2026-03-11

### Added

- persist rules to Room, message search, signal polling, About screen, key sharing [CUBEOS-70] (f20a655b)
- rules UI, Iridium message flow, mesh send, app icon, ProGuard [CUBEOS-70] (ac12f0a7)
- add BLE, SPP, rules engine, and wire transports into gateway service [CUBEOS-70] (7ff43392)
- initial Android app with SMS decrypt and MeshSat-dark UI [CUBEOS-70] (946fb0a9)

### Changed

- add MeshSat-to-MeshSat encrypted SMS section [CUBEOS-70] (391e2553)
- Initial commit: README and GPLv3 license [CUBEOS-70] (671fee20)

### Fixed

- increase Gradle heap for 8GB LXC, build passes [CUBEOS-70] (f00156cf)
- build config — Gradle wrapper, AndroidX, BuildConfig, memory tuning [CUBEOS-70] (048113db)

