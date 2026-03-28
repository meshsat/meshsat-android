package com.cubeos.meshsat.dtn

import com.cubeos.meshsat.reticulum.RnsForwardingTable

/**
 * DTN late binding — makes routing decisions at each hop based on
 * current conditions rather than at message origination time.
 *
 * Consults the forwarding table at forwarding time to pick the best
 * next-hop interface, considering which interfaces to exclude.
 */
class LateBinder(
    private val forwardingTable: RnsForwardingTable,
) {
    /**
     * Select the best interface for forwarding a packet to the given destination.
     *
     * @param destHash 16-byte destination hash
     * @param excludeInterfaces Interfaces to exclude (e.g., source interface for loop prevention)
     * @return Best interface ID, or null if no route available
     */
    fun selectInterface(
        destHash: ByteArray,
        excludeInterfaces: Set<String> = emptySet(),
    ): String? {
        val entry = forwardingTable.lookup(destHash) ?: return null
        if (entry.egressInterface !in excludeInterfaces) {
            return entry.egressInterface
        }
        // No alternative — let Dispatcher handle failover
        return null
    }
}
