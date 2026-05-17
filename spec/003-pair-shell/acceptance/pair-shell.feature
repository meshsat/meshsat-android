Feature: Android pair shell (spec/003)

  Background:
    Given the Android app launches for the first time
    And no paired_bridges row exists in Room v16

  # REQ-200..REQ-203 — splash
  Scenario: First-run splash shows 3 tiles
    Then a splash screen shows tiles "Standalone", "Pair with Bridge", "Both (advanced)"

  Scenario: Picking Standalone skips pair flow
    When the operator picks "Standalone"
    Then app.mode in DataStore is set to "standalone"
    And the home screen loads directly

  Scenario: Picking Pair routes to PairScannerScreen
    When the operator picks "Pair with Bridge"
    Then ui/screens/PairScannerScreen.kt opens with CameraX active

  # REQ-204 — Mode reentry
  Scenario: Settings → Mode reopens the splash
    Given the operator picked "Standalone" earlier
    When the operator opens Settings → Mode
    Then the three-way splash is shown again

  # REQ-205..212 + REQ-209/210 — pair-claim
  Scenario: Valid QR completes the pair flow
    Given a valid meshsat://pair/<base64> QR shown on a bridge
    When the operator scans
    Then the QR Ed25519 sig is verified with explicit "BC" provider
    And X25519 + Ed25519 client keypairs are generated in Android Keystore
    And TLS 1.3 connects to lan.host with SPKI pinned to QR.fp
    And POST /api/v2/pair/claim is sent with the expected body
    And on 200 the bearer JWT + cert are stored in Android Keystore / EncryptedSharedPreferences
    And a paired_bridges Room row is inserted

  Scenario: TLS SPKI mismatch aborts before POST
    Given the QR's `fp` value does NOT match the bridge's actual TLS SPKI
    When the operator scans
    Then an error "bridge identity mismatch" is displayed
    And NO POST is made

  # REQ-216 + REQ-217 + REQ-218 — hybrid shell
  Scenario: WebView Settings loads with bearer + pinned TLS
    Given the operator is paired with bridge B
    When the operator opens Settings (a WebView path)
    Then the WebView first request includes Authorization: Bearer <B's jwt>
    And the WebView's TLS SPKI is verified against B's stored cert_sha256

  Scenario: WebView TLS pin failure rejects load
    Given the bridge's TLS SPKI no longer matches the stored cert_sha256
    When the WebView attempts to load Settings
    Then the request is rejected with "bridge identity mismatch"
    And NO Settings content is rendered

  # REQ-219 + REQ-220 — source switcher
  Scenario: Source switcher shows This phone + paired bridges
    Given app.mode="both" and 2 paired bridges B1, B2 exist
    When the operator opens the Source switcher
    Then the drawer shows tiles "This phone", "B1", "B2"

  Scenario: Switching source reloads views within 2 seconds
    Given the operator is on InboxView reading B1's data
    When the operator selects "B2" in the Source switcher
    Then within 2 seconds InboxView shows B2's data instead

  # REQ-222 + REQ-223 — notifications
  Scenario: Notification tagged with source bridge_id navigates to source
    Given B1 publishes a new MO event
    When the system notification is rendered
    Then it is tagged with source bridge_id="B1"
    When the operator taps the notification
    Then the Source Switcher switches to B1
    And InboxView opens

  # REQ-224 — Article C-V enforcement
  Scenario: Private key bytes never in SharedPreferences
    Given a successful pair-claim
    When the operator inspects EncryptedSharedPreferences via debug tooling
    Then no entry contains the raw X25519 or Ed25519 private key bytes
    And the keys are reachable ONLY via Android Keystore alias

  # REQ-225 — remove-pairing flow
  Scenario: Removing a paired bridge wipes Keystore + Room + paired_bridge_* cascade
    Given paired bridge B exists with Keystore cert + paired_bridges row + paired_bridge_contacts rows
    When the operator removes B via Settings → Devices
    Then the Keystore alias for B is deleted
    And the paired_bridges row for B is deleted
    And all paired_bridge_* rows for B are deleted (FK cascade from spec/002)
