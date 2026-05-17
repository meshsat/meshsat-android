Feature: Android Pair Shell (meshsat-android Phase 8)

  Background:
    Given the Android app launches for the first time
    And no paired_bridges row exists

  # REQ-200 + REQ-201 + REQ-202 + REQ-203 — three-way splash
  Scenario: First-run splash shows 3 tiles
    When the app launches for the first time
    Then a splash screen shows tiles "Standalone", "Pair with Bridge", "Both (advanced)"

  Scenario: Picking Standalone skips the pair flow
    When the operator picks "Standalone"
    Then app.mode is set to "standalone"
    And the home screen loads directly

  Scenario: Picking Pair routes to the scanner
    When the operator picks "Pair with Bridge"
    Then PairScannerScreen opens with CameraX active

  # REQ-204 — re-entry via Settings
  Scenario: Settings → Mode reopens the splash
    Given the operator picked "Standalone" earlier
    When the operator opens Settings → Mode
    Then the three-way splash is shown again

  # REQ-205 + REQ-206 + REQ-211 — successful pair-claim
  Scenario: QR scan completes the pair flow
    Given a valid meshsat://pair/<base64> QR shown on the bridge's touch display
    When the operator scans the QR
    Then the Ed25519 signature is verified against the embedded signing pubkey
    And X25519 + Ed25519 keypairs are generated
    And TLS 1.3 connects to lan.host with SPKI pinned to QR.fp
    And POST /api/v2/pair/claim is sent with the expected body
    And on 200, the bearer JWT + cert are stored in Android Keystore
    And a paired_bridges Room row is inserted

  # REQ-210 — SPKI mismatch aborts
  Scenario: TLS SPKI mismatch aborts the pair-claim
    Given the QR's `fp` field does NOT match the bridge's actual TLS SPKI
    When the operator scans the QR
    Then an error "bridge identity mismatch" is displayed
    And NO POST is made

  # REQ-216 + REQ-217 + REQ-218 — hybrid shell + WebView pinning
  Scenario: WebView Settings loads with bearer + pinned TLS
    Given the operator is paired with bridge B
    When the operator opens Settings (a WebView path)
    Then the WebView's first request includes Authorization: Bearer <B's jwt>
    And the WebView's TLS SPKI is verified against B's stored cert_sha256

  Scenario: WebView TLS pin failure rejects load
    Given the bridge's TLS SPKI no longer matches the stored cert_sha256
    When the WebView attempts to load Settings
    Then the request is rejected with "bridge identity mismatch"
    And NO Settings content is rendered

  # REQ-219 + REQ-220 — source switcher
  Scenario: Source switcher shows This phone + paired bridges
    Given app.mode="both" + 2 paired bridges B1, B2
    When the operator opens the Source switcher
    Then the drawer shows tiles "This phone", "B1", "B2"

  Scenario: Switching source reloads views within 2 seconds
    Given the operator is on InboxView reading B1's data
    When the operator selects "B2" in the Source switcher
    Then within 2 seconds the InboxView shows B2's data instead

  # REQ-222 + REQ-223 — per-source notifications
  Scenario: Notification is tagged with source bridge_id and navigates back
    Given B1 publishes a new MO event
    When the system notification is rendered
    Then it is tagged with source bridge_id="B1"
    When the operator taps the notification
    Then the Source switcher switches to B1
    And the InboxView opens

  # REQ-224 — private key never in shared prefs
  Scenario: Private key is never persisted in SharedPreferences
    Given a successful pair-claim
    When the operator inspects the app's SharedPreferences via debug tooling
    Then no entry contains the raw client private key bytes
    And the key is reachable ONLY via Android Keystore alias

  # REQ-225 — remove pairing
  Scenario: Removing a paired bridge wipes Keystore + Room + cached snapshot
    Given paired bridge B exists with Keystore cert + Room row + cached snapshot
    When the operator removes B in Settings → Devices
    Then the Keystore alias for B is deleted
    And the paired_bridges row for B is deleted
    And any paired_bridge_* rows for B are deleted
