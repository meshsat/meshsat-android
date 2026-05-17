# Requirements — Android Pair Shell (meshsat-android Phase 8, EXECUTION-PLAN §6.8)

Source: `meshsat/EXECUTION-PLAN.md` §6.8 (Android stories S8-07..S8-12). Cross-cuts `meshsat/spec/001-pair-protocol/` (bridge side already shipped) AND `meshsat/spec/009-multi-bridge-nat/` (multi-bridge backend).

Constitution invariants in scope: Article III (BouncyCastle ordering), Article IV (MapScreen OUTSIDE NavHost), Article VI (java.util.Base64 + DAO interfaces directly), Article VIII (foreground service hygiene), Article XIII (single Room migration per version), Article XIV (Compose state correctness).

The Android pair shell makes the phone a controller for one OR many paired bridges + introduces the three-mode splash (Standalone / Pair-with-Bridge / Both) + the hybrid Compose+WebView shell (native for hot paths, WebView for Settings) + the multi-bridge Source switcher.

## Functional requirements

REQ-200: The Android app shall introduce a first-run three-way splash with tiles `Standalone`, `Pair with Bridge`, `Both (advanced)` per `meshsat/UX-MULTI-ACCESS-KIOSK-PAIRING.md §5.1`.

REQ-201: When the operator picks `Standalone`, the app shall set `app.mode=standalone` AND skip the pair-claim flow AND launch directly into the existing standalone gateway flow.

REQ-202: When the operator picks `Pair with Bridge`, the app shall navigate to the QR-scanner screen AND require a successful pair-claim before reaching the home screen.

REQ-203: When the operator picks `Both`, the app shall set `app.mode=both` AND start the standalone gateway AND require a pair-claim AND wire the Source switcher to allow per-view toggle.

REQ-204: The operator shall be able to re-enter the three-way splash via Settings → Mode.

REQ-205: The Android app shall implement a `PairScannerScreen.kt` using CameraX + ZXing to scan `meshsat://pair/...` QR codes from the bridge's touch display.

REQ-206: When a QR scans successfully, the app shall verify the Ed25519 signature on the QR payload against the bridge's embedded signing pubkey (per `meshsat/spec/001-pair-protocol/` REQ-002).

REQ-207: When the QR signature verifies, the app shall generate an X25519 ephemeral keypair + Ed25519 client identity + PKCS#10 CSR for the bridge to sign.

REQ-208: The app shall compute `ss = HKDF-SHA256(X25519(client_eph_priv, bridge_epk), info="meshsat-pair-v1")` per the pair-protocol design.

REQ-209: The app shall open a TLS 1.3 connection to `lan.host:lan.port` from the QR payload AND shall pin the bridge's TLS SPKI against the `fp` hash field from the QR.

REQ-210: When the TLS SPKI does NOT match the QR's `fp` field, the app shall abort with a clear "bridge identity mismatch" error AND shall NOT proceed.

REQ-211: The app shall POST to `/api/v2/pair/claim` with `{client_id, client_name, client_eph, client_spk, csr, mac=HMAC-SHA256(ss, client_id||client_eph||client_spk||csr)}` per the protocol spec.

REQ-212: When the pair-claim response returns 200 with `{bearer, cert, ca, rns, hub}`, the app shall store the bearer JWT + client private key in the Android Keystore (hardware-backed where available).

REQ-213: The app shall record the new `paired_bridges` Room row with `bridge_id, name, fingerprint, signing_pubkey, lan_host, lan_ip, lan_port, rns_dest, hub_url, cert_alias, bearer_jwt, jwt_exp, last_seen, health, added_at`.

REQ-214: The app shall add Room migration v15→v16 introducing the `paired_bridges` table.

REQ-215: The Room migration v15→v16 shall be the only schema change in this release per Constitution Article XIII (note: meshsat-android spec/002 already used v14→v15, so this is the next slot).

REQ-216: The Android app shall implement a hybrid shell: native Compose for hot paths (paired-bridges list, Compose, Inbox, Map, People) and WebView wrapping the Bridge's Vue SPA for cold paths (Settings, Interfaces, Rules, Audit, Spectrum).

REQ-217: The WebView shall be preloaded with `Authorization: Bearer <JWT>` AND a pinned TLS handler verifying bridge SPKI == stored `cert_sha256`.

REQ-218: When the WebView TLS pin fails on any request, the WebView shall reject the request with an explicit "bridge identity mismatch" error AND shall NOT load.

REQ-219: The Android app shall implement a Source switcher rendered as a top-of-screen drawer showing `This phone` (if mode=standalone OR mode=both) AND every paired bridge.

REQ-220: When the operator selects a different source in the Source switcher, the app shall switch every native view's data scope to that source within 2 seconds.

REQ-221: When the operator selects the WebView (Settings) for a source, the WebView's base URL shall point to that source's bridge AND the bearer JWT shall be that bridge's stored token.

REQ-222: The per-source notifications (REQ-803 from `meshsat/spec/009-multi-bridge-nat/`) shall be tagged with the source `bridge_id` in the Android notification.

REQ-223: When the operator taps a per-source notification, the app shall switch the Source switcher to that source AND navigate to the relevant view.

REQ-224: The pair-claim flow shall NEVER store the raw client private key in shared preferences OR DataStore — only the Android Keystore alias.

REQ-225: When the operator removes a paired bridge via Settings → Devices → Remove, the app shall delete the Keystore-stored cert+key AND the `paired_bridges` row AND any cached snapshot data for that bridge.

REQ-226: The hybrid shell's WebView client shall inject the bearer JWT into Authorization headers via an OkHttp interceptor — NOT via cookies, NOT via localStorage — to defend against XSS exfiltration.
