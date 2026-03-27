package com.cubeos.meshsat.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-instance transport registry. Holds all transport instances keyed by
 * interface ID (e.g., "ble_mesh_0", "iridium_spp_0", "iridium_imt_0").
 *
 * Backward compatible: existing singleton accessors in GatewayService.companion
 * continue to work by delegating to the first instance of each type.
 */
class TransportRegistry {
    private val transports = ConcurrentHashMap<String, Any>()

    fun register(id: String, transport: Any) {
        transports[id] = transport
    }

    fun unregister(id: String) {
        transports.remove(id)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(id: String): T? = transports[id] as? T

    @Suppress("UNCHECKED_CAST")
    fun <T> getAll(type: Class<T>): Map<String, T> =
        transports.filterValues { type.isInstance(it) }.mapValues { it.value as T }

    fun ids(): Set<String> = transports.keys.toSet()

    fun size(): Int = transports.size

    fun clear() {
        transports.clear()
    }
}
