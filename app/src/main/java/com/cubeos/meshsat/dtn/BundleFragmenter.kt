package com.cubeos.meshsat.dtn

/**
 * Fragments large payloads into MTU-sized bundles with fragment headers.
 * Each fragment carries a shared bundle_id for reassembly at the destination.
 */
object BundleFragmenter {

    /**
     * Fragment a payload into MTU-sized pieces.
     *
     * @param payload Original data
     * @param mtu Maximum transmission unit (e.g., 340 for Iridium SBD)
     * @return List of fragments, each with FragmentHeader prepended
     */
    fun fragment(payload: ByteArray, mtu: Int): List<ByteArray> {
        val headerSize = DtnProtocol.FragmentHeader.SIZE
        val dataPerFragment = mtu - headerSize
        require(dataPerFragment > 0) { "MTU ($mtu) too small for fragment header ($headerSize)" }

        val bundleId = DtnProtocol.generateId()
        val fragmentCount = (payload.size + dataPerFragment - 1) / dataPerFragment

        if (fragmentCount <= 1) {
            // No fragmentation needed — but still wrap with header for consistency
            val header = DtnProtocol.FragmentHeader(bundleId, 0, 1, payload.size)
            return listOf(header.marshal() + payload)
        }

        return (0 until fragmentCount).map { i ->
            val offset = i * dataPerFragment
            val len = minOf(dataPerFragment, payload.size - offset)
            val data = payload.copyOfRange(offset, offset + len)
            val header = DtnProtocol.FragmentHeader(bundleId, i, fragmentCount, payload.size)
            header.marshal() + data
        }
    }

    /**
     * Extract the fragment header and payload from a fragment.
     *
     * @return Pair of (header, payload data) or null if invalid
     */
    fun parseFragment(fragment: ByteArray): Pair<DtnProtocol.FragmentHeader, ByteArray>? {
        val header = DtnProtocol.FragmentHeader.unmarshal(fragment) ?: return null
        if (fragment.size < DtnProtocol.FragmentHeader.SIZE) return null
        val data = fragment.copyOfRange(DtnProtocol.FragmentHeader.SIZE, fragment.size)
        return Pair(header, data)
    }
}
