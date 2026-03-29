package com.cubeos.meshsat.hemb

/**
 * Android-specific bearer selector — builds HembBearerProfile list from
 * the available transports (BLE, SMS, cellular) registered at runtime.
 *
 * The bearer set is dynamic: transports come and go based on BLE connection
 * state, SIM availability, and cellular signal. BearerSelector re-evaluates
 * on each send to reflect current transport health.
 */
class BearerSelector {

    private val registeredBearers = mutableMapOf<String, BearerRegistration>()

    data class BearerRegistration(
        val interfaceId: String,
        val channelType: String,
        val mtu: Int,
        val costPerMsg: Double,
        val lossRate: Double,
        val latencyMs: Int,
        val relayCapable: Boolean,
        val headerMode: String,
        val sendFn: suspend (ByteArray) -> Unit,
        val healthFn: () -> Int,   // returns 0-100 health score
        val onlineFn: () -> Boolean,
    )

    /** Register a transport as an HeMB bearer. */
    fun register(reg: BearerRegistration) {
        registeredBearers[reg.interfaceId] = reg
    }

    /** Unregister a transport. */
    fun unregister(interfaceId: String) {
        registeredBearers.remove(interfaceId)
    }

    /**
     * Build the current bearer profile list from registered transports.
     * Only includes online bearers with health > 0.
     */
    fun activeBearers(): List<HembBearerProfile> {
        var index = 0
        return registeredBearers.values
            .filter { it.onlineFn() && it.healthFn() > 0 }
            .map { reg ->
                HembBearerProfile(
                    index = index++,
                    interfaceId = reg.interfaceId,
                    channelType = reg.channelType,
                    mtu = reg.mtu,
                    costPerMsg = reg.costPerMsg,
                    lossRate = reg.lossRate,
                    latencyMs = reg.latencyMs,
                    healthScore = reg.healthFn(),
                    sendFn = reg.sendFn,
                    relayCapable = reg.relayCapable,
                    headerMode = reg.headerMode,
                )
            }
    }

    /** Returns all registered interface IDs (including offline). */
    fun registeredIds(): List<String> = registeredBearers.keys.toList()

    /** Returns count of currently online bearers. */
    val onlineCount: Int get() = registeredBearers.values.count { it.onlineFn() }

    companion object {
        // Standard Android bearer registration helpers.

        /** BLE Meshtastic bearer profile defaults. */
        fun bleDefaults() = BearerRegistration(
            interfaceId = "ble_0",
            channelType = "mesh",
            mtu = 237,
            costPerMsg = 0.0,
            lossRate = 0.15,
            latencyMs = 500,
            relayCapable = true,
            headerMode = HembFrame.HEADER_MODE_COMPACT,
            sendFn = { throw NotImplementedError("Wire sendFn at registration") },
            healthFn = { 0 },
            onlineFn = { false },
        )

        /** SMS bearer profile defaults. */
        fun smsDefaults() = BearerRegistration(
            interfaceId = "sms_0",
            channelType = "sms",
            mtu = 140, // binary SMS payload
            costPerMsg = 0.0, // depends on plan
            lossRate = 0.05,
            latencyMs = 3000,
            relayCapable = true,
            headerMode = HembFrame.HEADER_MODE_COMPACT,
            sendFn = { throw NotImplementedError("Wire sendFn at registration") },
            healthFn = { 0 },
            onlineFn = { false },
        )

        /** Cellular data bearer profile defaults. */
        fun cellularDefaults() = BearerRegistration(
            interfaceId = "cellular_0",
            channelType = "cellular",
            mtu = 65535,
            costPerMsg = 0.0,
            lossRate = 0.02,
            latencyMs = 100,
            relayCapable = true,
            headerMode = HembFrame.HEADER_MODE_EXTENDED,
            sendFn = { throw NotImplementedError("Wire sendFn at registration") },
            healthFn = { 0 },
            onlineFn = { false },
        )

        /** Iridium SPP bearer profile defaults. */
        fun iridiumSppDefaults() = BearerRegistration(
            interfaceId = "iridium_spp_0",
            channelType = "iridium_sbd",
            mtu = 340,
            costPerMsg = 0.05,
            lossRate = 0.01,
            latencyMs = 30000,
            relayCapable = true,
            headerMode = HembFrame.HEADER_MODE_EXTENDED,
            sendFn = { throw NotImplementedError("Wire sendFn at registration") },
            healthFn = { 0 },
            onlineFn = { false },
        )
    }
}
