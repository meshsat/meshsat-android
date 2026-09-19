package net.meshsat.android.hub.relay

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The whole path against a real Hub and a real bridge (MESHSAT-1157): tunnel through
 * `wss://<hub>/api/relay/connect/<bridge>`, TLS inside it with the phone's Hub-issued
 * certificate, `GET /health` on the bridge's API expecting 200.
 *
 * Skipped unless `MESHSAT_RELAY_LIVE=1`. It needs a bridge of the same tenant serving
 * `GET /api/relay/serve` (Bridge side, MESHSAT-613) and a phone identity of that tenant.
 *
 * Environment:
 *   MESHSAT_RELAY_HUB       Hub API base, e.g. https://hub.meshsat.net
 *   MESHSAT_RELAY_BRIDGE    target bridge id (the kit)
 *   MESHSAT_RELAY_ID        this end's bridge id (the phone's, type android)
 *   MESHSAT_RELAY_PASSWORD  this end's Hub MQTT password
 *   MESHSAT_RELAY_CERT      path to this end's Hub-issued certificate PEM
 *   MESHSAT_RELAY_KEY       path to its private key PEM
 *   MESHSAT_RELAY_CA        path to the Hub bridge CA PEM (optional: fetched from
 *                           GET <hub>/api/relay/ca when unset)
 *
 * The kit side is live on production: nllei01tesseract01 serves its API through the
 * Hub relay, so it is the target once a phone identity of the same tenant exists.
 *
 * Example (on the SDK VM, never on the runner host):
 *   MESHSAT_RELAY_LIVE=1 MESHSAT_RELAY_HUB=https://hub.meshsat.net \
 *   MESHSAT_RELAY_BRIDGE=nllei01tesseract01 MESHSAT_RELAY_ID=phone-1 \
 *   MESHSAT_RELAY_PASSWORD=... MESHSAT_RELAY_CERT=phone-1.crt MESHSAT_RELAY_KEY=phone-1.key \
 *   ./gradlew --no-daemon --offline testDebugUnitTest --tests 'net.meshsat.android.hub.relay.RelayLiveTest'
 */
class RelayLiveTest {

    private fun env(name: String): String =
        System.getenv(name)?.trim().orEmpty().also { assumeTrue("$name not set", it.isNotBlank()) }

    @Test
    fun `tunnel then inner TLS then GET health on the bridge is 200`() {
        assumeTrue("MESHSAT_RELAY_LIVE is not 1", System.getenv("MESHSAT_RELAY_LIVE") == "1")
        val hub = env("MESHSAT_RELAY_HUB")
        val bridge = env("MESHSAT_RELAY_BRIDGE")
        val ownId = env("MESHSAT_RELAY_ID")
        val password = env("MESHSAT_RELAY_PASSWORD")
        val cert = File(env("MESHSAT_RELAY_CERT")).readText()
        val key = File(env("MESHSAT_RELAY_KEY")).readText()
        val caPath = System.getenv("MESHSAT_RELAY_CA")?.trim().orEmpty()
        val ca = if (caPath.isNotBlank()) File(caPath).readText() else RelayHttp.fetchHubCa(hub)
        assertTrue("Hub CA is a certificate", ca.contains("BEGIN CERTIFICATE"))

        val log = StringBuilder()
        val tunnel = RelayTunnel(hub, bridge, ownId, password, log = { log.append(it).append('\n') })
        try {
            tunnel.open()
            val state = runBlocking {
                withTimeout(20_000) {
                    tunnel.state.first { it !is RelayTunnel.RelayState.Connecting }
                }
            }
            assertTrue("tunnel did not open: $state\n$log", state is RelayTunnel.RelayState.Open)
            val port = (state as RelayTunnel.RelayState.Open).localPort
            assertTrue(port > 0)

            val http = RelayHttp(port, bridge, cert, key, ca)
            val resp = http.get("/health")
            assertEquals("GET /health via relay to $bridge: ${resp.text}\n$log", 200, resp.code)
            println("relay live: GET /health on $bridge via $hub -> ${resp.code} ${resp.text}")
        } finally {
            tunnel.close()
        }
    }
}
