package net.meshsat.android.sos

import org.json.JSONObject

/**
 * What an SOS needs of the Hub session (C10, MESHSAT-1584): [net.meshsat.android.hub.HubReporter],
 * or a test's. Both publish calls block until the broker has the message; false when the Hub is
 * not connected or the publish failed.
 */
interface SosHubLink {
    val bridgeId: String
    val isConnected: Boolean
    fun publishSos(
        deviceId: String,
        id: String,
        text: String,
        sos: Boolean,
        type: String,
        lat: Double?,
        lon: Double?,
        asMessage: Boolean = true,
    ): Boolean
    fun publishSosEvent(event: JSONObject): Boolean
}
