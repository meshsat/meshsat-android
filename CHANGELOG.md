# Changelog — meshsat-android

All notable changes to this project. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) + [Conventional Commits](https://www.conventionalcommits.org/).

Generated from git history + tags by `scripts/sdd-generate-changelog.py` on 2026-05-18.


## [v2.8.6] - 2026-04-05

### Added

- local release telemetry ring buffer [MESHSAT-494] (f6db9324)

### Changed

- drop radio-config + audit-log screenshots, keep hero only [MESHSAT-496] (9a562e76)
- remove features-menu screenshot (didn't render well) [MESHSAT-496] (ad954c59)
- comprehensive rewrite + 6 screenshots [MESHSAT-496] (59ea95b3)
- comprehensive README overhaul + GitHub metadata script [MESHSAT-496] (cebcbca9)


## [v2.8.5] - 2026-04-05

### Added

- TOFU key pinning in KeyBundleImporter + bundle v2 [MESHSAT-495] (f1ea6960)


## [v2.8.4] - 2026-04-05

### Changed

- bump version to v2.8.4 (46ef3e01)

### Fixed

- gate all I/O on connection state, silence disconnected-state spam [MESHSAT-499] (6ae63557)


## [v2.8.3] - 2026-04-05

### Fixed

- correct ms/sec unit mismatch in PassScheduler OOM [MESHSAT-498] (07f405aa)
- cache pass predictions to prevent OOM in PassScheduler [MESHSAT-498] (165dc7ec)


## [v2.8.2] - 2026-04-05

### Fixed

- move BouncyCastle to end of JCA provider chain [MESHSAT-497] (4ece0ea1)


## [v2.8.1] - 2026-04-05

### Changed

- bump version to v2.8.1 (a4b3e19b)

### Fixed

- add osmdroid keep rules to prevent white map tiles on release [MESHSAT-493] (e31a8a46)


## [v2.8.0] - 2026-04-05

### Added

- add release signing with OpenBao + Play Store AAB (32f3652c)

### Changed

- bump version to v2.8.0 (4673059c)

### Fixed

- add dontwarn rules for Tink compile-time annotations (ad4499dd)


## [v2.7.0] - 2026-04-04

### Added

- receive TAK positions from Hub broadcast + settings API [MESHSAT-448] (b64ab9a2)
- TAK/CoT-compliant Canvas markers — diamond, square, emergency [MESHSAT-464] (d286d672)
- protobuf v1 + waypoint builder + enriched position [MESHSAT-456] (8c400ff4)
- add TAK Settings section with enable/callsign/output toggles [MESHSAT-451] (e122a0ac)
- auto-forward + /api/sms/auto-forward endpoint [MESHSAT-447] (c3425937)
- add /api/sms/send to Android LocalApiServer [MESHSAT-447] (296cf1b6)
- Android outbound SMAZ2 compress + wildcard key encrypt [MESHSAT-447] (6d638416)
- port SMAZ2 compressor/decompressor to Kotlin [MESHSAT-447] (f426352d)

### Changed

- bump version to v2.7.0 (1175f6aa)

### Fixed

- replace zoomToBoundingBox with direct center+zoom [MESHSAT-479] (9122bad8)
- debounce Room position Flow to prevent ANR from marker burst [MESHSAT-479] (5be19540)
- prevent ANR from repeated zoomToBoundingBox on marker updates [MESHSAT-479] (67b563a8)
- subscribe to TAK broadcast topic for fleet-wide positions [MESHSAT-448] (a3a9cb0d)
- use CotPrecision camelCase field names (altSrc, geoPointSrc) [MESHSAT-456] (7e6dc96f)
- use explicit getters for PrecisionLocation proto fields [MESHSAT-456] (dfe31b7d)
- use java_package imports for proto-generated classes [MESHSAT-456] (d1d66756)
- correct protobuf class names for java_multiple_files + proto3 [MESHSAT-456] (c2c928e8)
- use explicit hasXxx() checks in protoToCotEvent [MESHSAT-456] (67855d65)
- show SmsReceiver-decrypted text in Messages UI [MESHSAT-447] (7f96ca55)
- fall back to Hub wildcard key (sms:*) for SMS decryption [MESHSAT-447] (5bdd2203)
- synchronize RLNC decoder to prevent concurrent ArrayIndexOutOfBounds [MESHSAT-447] (5c77d3b8)
- wire HeMB inbound detection on Android RnsTransportNode [MESHSAT-443] (9ea3c81b)


## [v2.6.1] - 2026-03-31

### Changed

- bump version to v2.6.1 (c08e3ded)

### Fixed

- backport 2 HeMB bug fixes from bridge E2E integration [MESHSAT-415] (6282976b)


## [v2.6.0] - 2026-03-29

### Added

- port HeMB bearer bonding protocol to Android [MESHSAT-431] (f83a402c)

### Changed

- bump version to v2.6.0 (fcad2c20)
- rewrite README to match v2.5.0 feature set [MESHSAT-354] (8652838c)


## [v2.5.0] - 2026-03-29

### Added

- TLS+mTLS for TCP interface, BouncyCastle for Android 16 [MESHSAT-354] (4bd91ac6)

### Changed

- bump version to v2.5.0 (545cf856)


## [v2.4.1] - 2026-03-29

### Fixed

- ping button uses HubReporter client, not MqttTransport (f38fb17d)


## [v2.4.0] - 2026-03-29

### Fixed

- mTLS trust store must include system CAs alongside custom CA (ad7068af)


## [v2.3.1] - 2026-03-29

### Added

- ECDSA-P256 signed birth messages for Hub verification (1dfa61b6)


## [v2.3.0] - 2026-03-29

### Added

- health LED, ping button, QR auto-populate fix (78a61af2)


## [v2.2.2] - 2026-03-28

### Fixed

- support both inline and nonce QR formats (1419425d)


## [v2.2.1] - 2026-03-28

### Added

- two-step QR provisioning (nonce URL + HTTPS claim) (b397165d)


## [v2.2.0] - 2026-03-28

### Added

- QR code Hub provisioning with mTLS auto-config (c2c049aa)


## [v2.1.0] - 2026-03-28

### Added

- wire MQTT Reticulum interface for Hub interop [MESHSAT-354] (42e6fba0)


## [v2.0.1] - 2026-03-28

### Added

- add Cellular NITZ as stratum 0 time source [MESHSAT-410] (475b3d92)

### Fixed

- paid interface filter + bridge-compatible wire formats [MESHSAT-414] (192824e7)


## [v2.0.0] - 2026-03-28

### Added

- DTN + FEC + Time Sync + RLNC protocol enhancements [MESHSAT-407] [MESHSAT-408] [MESHSAT-409] [MESHSAT-410] [MESHSAT-411] (6961c7ca)


## [v1.9.1] - 2026-03-28

### Fixed

- use online OSM tiles — MBTiles offline never rendered (8d75d804)


## [v1.9.0] - 2026-03-28

### Fixed

- use osmdroid auto-detect for MBTiles + MapScreen outside NavHost (24ec8ded)


## [v1.8.5] - 2026-03-28

### Fixed

- move MapScreen outside NavHost to prevent view detachment (62f7468b)


## [v1.8.4] - 2026-03-27

### Fixed

- restart tile provider threads after view re-attach (86fbc747)


## [v1.8.3] - 2026-03-27

### Fixed

- singleton MapView to survive Compose NavHost tab switches (56932e21)


## [v1.8.2] - 2026-03-27

### Fixed

- proper osmdroid lifecycle management for tile persistence (f7334de1)


## [v1.8.1] - 2026-03-27

### Fixed

- tile provider persistence and dark mode color inversion (11a5f174)


## [v1.8.0] - 2026-03-27

### Added

- replace Leaflet/WebView with osmdroid native map rendering (ac4f5e02)


## [v1.7.1] - 2026-03-27

### Added

- vector tile rendering, offline-first, bundled world map [MESHSAT-402] (d5a8dd4d)
- vector tile rendering with dark/light themes and offline-first default (c8cc0d15)

### Changed

- bump version to v1.7.1 (fd44f774)


## [v1.6.0] - 2026-03-27

### Added

- offline MBTiles map tile support (2237f093)

### Changed

- bump version to v1.6.0 (d9df407f)


## [v1.5.5] - 2026-03-27

### Changed

- bump version to v1.5.5 (53c27681)

### Fixed

- nuclear reset for corrupted EncryptedSharedPreferences (6e083d76)


## [v1.5.4] - 2026-03-27

### Fixed

- load tiles via WebViewAssetLoader instead of file:// origin (5705155b)


## [v1.5.3] - 2026-03-27

### Changed

- bump version to v1.5.3 (fa11c6f7)

### Fixed

- handle corrupted EncryptedSharedPreferences on startup (ca71986d)


## [v1.5.2] - 2026-03-27

### Changed

- bump version to v1.5.2 (18b7e3d4)

### Fixed

- foreground service crash on Android 14+ when BT permission denied (b30a5ea0)


## [v1.5.1] - 2026-03-27

### Added

- dashboard widget reorder dialog with persistent order [MESHSAT-401] (a7e0e131)

### Changed

- bump version to v1.5.1 (29bba386)


## [v1.5.0] - 2026-03-27

### Added

- Iridium credit tracking and gauge visualization [MESHSAT-399] (6b29b551)
- Reticulum widget and dashboard enhancements [MESHSAT-396] [MESHSAT-397] [MESHSAT-398] [MESHSAT-400] (1352ccba)
- credential management screen with PEM import [MESHSAT-391] (913581ec)
- wire pass scheduler, mTLS, registry, and TCP peers [MESHSAT-386] [MESHSAT-387] [MESHSAT-388] [MESHSAT-392] (d8dd26d0)
- announce interval and routing config UI [MESHSAT-394] (32bb6677)
- YAML config export/import matching Bridge format [MESHSAT-393] (16a735ba)
- TCP multi-peer management with Room DB [MESHSAT-392] (ad000fec)
- multi-instance transport registry [MESHSAT-388] (ace56e41)
- mTLS client certificate support for Hub MQTT/NATS [MESHSAT-387] (4beeec04)
- pass-aware scheduling — 4-mode transport polling [MESHSAT-386] (69f89cae)
- add service restart API and UI button [MESHSAT-395] (3ea511d2)
- implement send_mt, flush_burst, config_update, reboot commands [MESHSAT-390] (732dead6)
- preserve OTA-learned node identity on config download merge [MESHSAT-389] (ed0f1aac)
- credential import via Hub sync + QR bundles [MESHSAT-369] (e6cc52ad)

### Changed

- bump version to v1.5.0 (d4ab14c7)


## [v1.4.0] - 2026-03-23

### Added

- add TCP_HDLC encapsulation enum for stock RNS wire compat [MESHSAT-199] (757aac7b)
- HubReporter — bridge-to-hub uplink protocol for Android [MESHSAT-292] (5368bfb3)
- add RnsAstrocastInterface + GatewayService integration [MESHSAT-12] (a3cf8abe)
- add TCP, BLE peripheral, Tor, and WireGuard interfaces [MESHSAT-268/269/270/271] (e33ad781)
- wire RnsTransportNode into GatewayService lifecycle [MESHSAT-267] (dc529803)
- add RnsIridium9704Interface for Reticulum over IMT [MESHSAT-272] (13e6b72b)
- add Transport Node with forwarding table and packet relay [MESHSAT-199] (c92dbcdf)
- integrate Iridium 9704 transport into GatewayService [MESHSAT-245] (e7bd29f3)
- add RockBLOCK 9704 IMT transport via Bluetooth SPP [MESHSAT-245] (5dd7fbc7)
- add Meshtastic radio config tab with all settings [MESHSAT-243] (8bc2f4e7)
- adopt official Meshtastic protobuf bindings [MESHSAT-241] (d724e4ef)

### Changed

- bump version to v1.4.0 (4284781a)
- add RnsIridium9704Interface unit tests [MESHSAT-272] (d3d5bebf)


## [v1.3.4] - 2026-03-20

### Changed

- bump version to v1.3.4 (157a1192)

### Fixed

- start service after permissions + bundle Leaflet locally (a75e5653)


## [v1.3.3] - 2026-03-20

### Changed

- bump version to v1.3.3 (5ac0e285)

### Fixed

- crash on start + map always visible (aaa30ab1)


## [v1.3.2] - 2026-03-20

### Changed

- bump version to v1.3.2 (2e647817)

### Fixed

- crash after 1min + no cell tower location (b284a7ac)


## [v1.3.1] - 2026-03-20

### Changed

- bump version to v1.3.1 (84192ec7)

### Fixed

- maps not loading — WebView needs non-null base URL for external resources (7336a0ae)


## [v1.3.0] - 2026-03-20

### Added

- add message fragmentation support — parity with Bridge [MESHSAT-234] (6ecfeda3)
- wire directed messaging with ACK/REJ into GatewayService [MESHSAT-232] (3ba23ac1)
- directed messaging with ACK/REJ tracking [MESHSAT-232] (3d636466)
- add smart position beaconing with corner pegging [MESHSAT-231] (20c90736)
- add smart position beaconing with corner pegging [MESHSAT-231] (d55919ac)
- APRS-IS direct connect — standalone APRS station without APRSDroid [MESHSAT-230] (b3abe9c5)
- Astronode S serial protocol driver + SPP transport [MESHSAT-12] (b2afce6d)
- CoT v2.0 generation + ATAK integration + inbound CoT parsing [MESHSAT-191] (ca141565)
- QR code key sync with Hub, AES-GCM wire format tests [MESHSAT-205] (df1dcffa)
- per-channel compression toggle — disable MSVQ-SC for Iridium [MESHSAT-203] (89f8b47f)
- bidirectional SMS relay between Android and Hub via MQTT [MESHSAT-196] (8b6529f8)
- path discovery with cost-aware routing, reliable resource transfer [MESHSAT-222] [MESHSAT-223] (dd9e4061)
- 5 transport interfaces — BLE, SMS, Iridium, APRS, MQTT [MESHSAT-213] [MESHSAT-215] [MESHSAT-216] [MESHSAT-218] [MESHSAT-220] (92616794)
- Meshtastic BLE interface adapter for Reticulum packets [MESHSAT-213] (e67bd36b)
- ECDH link handshake with HKDF key derivation [MESHSAT-211] (d80cfa06)
- wire-compatible announce with 64-byte public_key field [MESHSAT-209] (5f92f05f)
- wire-compatible packet format, destination hash, announce data [MESHSAT-208] (b19f3f83)
- protocol version byte, SBD fragmentation, cert pinning, APRS UI [MESHSAT-190] [MESHSAT-192] [MESHSAT-195] (64fb7e55)
- Android APRS adapter — KISS codec, AX.25, APRS encode/decode [MESHSAT-138] (2f75599b)
- Android MQTT client for Hub connectivity [MESHSAT-137] (c6b19978)

### Changed

- bump version to v1.3.0 (839a0082)

### Fixed

- align CRC byte order with Go bridge + cross-impl test vectors [MESHSAT-233] (436bd137)

### Security

- hardware-backed Android Keystore for all crypto keys [MESHSAT-194] (dfadad3e)


## [v1.2.1] - 2026-03-16

### Fixed

- lazy SMS permission request with banner in Messages screen (96ba9a07)


## [v1.2.0] - 2026-03-16

### Added

- Peers screen, message tabs, map layers, status bar, topology stats, interface tabs [MESHSAT-74] [MESHSAT-75] (7168493e)
- dashboard overhaul + pass elevation chart [MESHSAT-72] [MESHSAT-73] (75779b13)


## [v1.1.1] - 2026-03-16

### Fixed

- maps tiles, SMS permissions, navigation redesign (ff9a7de5)


## [v1.1.0] - 2026-03-16

### Added

- Phase J satellite pass predictor [MESHSAT-69] (ab942598)
- add radio config + device admin screen [MESHSAT-68] (64ec5534)
- Phase I interface management screen [MESHSAT-67] (76462997)
- Phase H bridge rules management UI [MESHSAT-66] (53296e93)

### Fixed

- audit log screen (Android), battery/lifecycle, dynamic interfaces, reboot confirm [MESHSAT-70] [MESHSAT-71] (8410f45f)


## [v1.0.2] - 2026-03-16

### Fixed

- maps grey square, invasive SMS perms, add dark/light theme toggle (ae8e42d6)


## [v1.0.1] - 2026-03-16

### Fixed

- crash on service creation — defensive startForeground + try/catch init (237fe72c)


## [v1.0.0] - 2026-03-16

### Added

- close MESHSAT-57 parity gaps — dedup wiring, telemetry, UI fixes, 59 unit tests [MESHSAT-57] (dfa02167)
- Phase G UI parity — settings, topology, deliveries, geofence [MESHSAT-65] (edd2e247)
- Phase F config, signing, and local REST API [MESHSAT-64] (50279374)
- Phase E routing layer — identity, announce, link manager [MESHSAT-63] (f4ba2fbc)
- Phase D field intelligence — deadman, geofence, health, codecs, burst [MESHSAT-62] (a7f3e57a)
- Phase C transport hardening — state machine, store-forward, QoS [MESHSAT-61] (3f6f8b85)
- port Go gateway routing to Kotlin — Phase B [MESHSAT-60] (9686718d)
- add core infrastructure — channel registry, dedup, rate limiter, transform pipeline [MESHSAT-59] (c3616257)
- add MSVQ-SC compression settings UI [MESHSAT-28] (ed0c47fb)
- MSVQ-SC lossy semantic compression for SMS [MESHSAT-28] (92439b5c)

### Changed

- README rewrite, issue templates, contributing guide [MESHSAT-57] (27079018)
- add pipeline for APK build + GitLab/GitHub releases [CUBEOS-72] (1de5dab5)

### Fixed

- audit fixes — ProGuard rules, tensor leak, thread safety, NaN guard (5f82f507)
- use bash to run gradlew (chmod fails on read-only checkout) (e9f184fb)


## [v0.3.0] - 2026-03-11

### Added

- chat replies, per-conv encryption, GPS map, device info, notifications [CUBEOS-72] (16ed3456)

### Fixed

- SMS detection, per-conversation keys, signal graphs [CUBEOS-72] (8428e112)


## [v0.2.0] - 2026-03-11

### Added

- conversations, map, SOS, signal graphs, bug fixes [CUBEOS-72] (a8478141)
- register as default SMS app candidate for GrapheneOS [CUBEOS-70] (9017d0eb)

### Fixed

- keep Rules tab, move Crypto to Settings, remove duplicate screens [CUBEOS-72] (6160ac9b)
- BLE scan, SMS decrypt logging, message UX improvements [CUBEOS-72] (3f6a5a3b)


## [v0.1.0] - 2026-03-11

_No commits within window._


## [v0.1.1] - 2026-03-11

### Added

- persist rules to Room, message search, signal polling, About screen, key sharing [CUBEOS-70] (d7caac63)
- rules UI, Iridium message flow, mesh send, app icon, ProGuard [CUBEOS-70] (647f5ca7)
- add BLE, SPP, rules engine, and wire transports into gateway service [CUBEOS-70] (5095378e)
- initial Android app with SMS decrypt and MeshSat-dark UI [CUBEOS-70] (f9631fb4)

### Changed

- add MeshSat-to-MeshSat encrypted SMS section [CUBEOS-70] (50ca040d)
- Initial commit: README and GPLv3 license [CUBEOS-70] (e892cf79)

### Fixed

- increase Gradle heap for 8GB LXC, build passes [CUBEOS-70] (adcc5519)
- build config — Gradle wrapper, AndroidX, BuildConfig, memory tuning [CUBEOS-70] (92fdac5e)

