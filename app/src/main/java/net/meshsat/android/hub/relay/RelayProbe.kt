package net.meshsat.android.hub.relay

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * One request to a kit's own API through the Hub relay, timed (MESHSAT-616).
 *
 * The relay's point is that a phone with no way into the kit's network - the kit behind
 * carrier-grade NAT, the phone on mobile data - can still reach the kit, because both ends dial
 * out to the Hub. The phone's relay link carries Reticulum frames, which says nothing about
 * whether the kit's API answers. This asks it: a tunnel in local-port mode, TLS inside it with the
 * phone's Hub-issued certificate against the kit's, one GET, and the time each step took.
 *
 * The Hub keeps one tunnel per identity, so this displaces the phone's running relay link for as
 * long as it lasts; that link waits a minute after being displaced and then comes back by itself.
 */
object RelayProbe {

    data class Result(
        val target: String,
        val path: String,
        val code: Int,
        val body: String,
        val tunnelMs: Long,
        val requestMs: Long,
        val error: String,
    ) {
        val totalMs: Long get() = tunnelMs + requestMs
    }

    fun run(
        hubApiBase: String,
        target: String,
        ownId: String,
        password: String,
        clientCertPem: String,
        clientKeyPem: String,
        caPem: String,
        path: String = "/health",
        timeoutMs: Long = 20_000,
    ): Result {
        val log = StringBuilder()
        val tunnel = RelayTunnel(hubApiBase, target, ownId, password, log = { log.append(it).append('\n') })
        val started = System.currentTimeMillis()
        try {
            tunnel.open()
            val state = runBlocking {
                withTimeout(timeoutMs) { tunnel.state.first { it !is RelayTunnel.RelayState.Connecting } }
            }
            val tunnelMs = System.currentTimeMillis() - started
            if (state !is RelayTunnel.RelayState.Open) {
                return Result(target, path, 0, "", tunnelMs, 0, "tunnel did not open: $state")
            }
            val t0 = System.currentTimeMillis()
            val resp = RelayHttp(state.localPort, target, clientCertPem, clientKeyPem, caPem).get(path)
            return Result(target, path, resp.code, resp.text.take(300), tunnelMs, System.currentTimeMillis() - t0, "")
        } catch (e: Exception) {
            return Result(
                target, path, 0, "", System.currentTimeMillis() - started, 0,
                (e.message ?: e.javaClass.simpleName) + if (log.isNotEmpty()) " | ${log.toString().trim().takeLast(300)}" else "",
            )
        } finally {
            tunnel.close()
        }
    }
}
