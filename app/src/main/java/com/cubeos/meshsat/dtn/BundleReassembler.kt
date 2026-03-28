package com.cubeos.meshsat.dtn

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Reassembles fragments by bundle_id.
 * Thread-safe — fragments can arrive from multiple interfaces/threads.
 */
class BundleReassembler(
    private val timeoutMs: Long = 300_000L, // 5 minutes default
) {
    companion object {
        private const val TAG = "BundleReassembler"
    }

    private data class PendingBundle(
        val totalSize: Int,
        val fragmentCount: Int,
        val fragments: MutableMap<Int, ByteArray> = mutableMapOf(), // index → data
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val pending = ConcurrentHashMap<String, PendingBundle>()

    /**
     * Feed a fragment. Returns the reassembled payload if all fragments
     * have arrived, or null if more are needed.
     *
     * @param fragment Raw fragment bytes (with FragmentHeader)
     * @return Complete payload, or null
     */
    fun feed(fragment: ByteArray): ByteArray? {
        val (header, data) = BundleFragmenter.parseFragment(fragment) ?: return null
        val key = header.bundleId.toHex()

        // Single fragment — return immediately
        if (header.fragmentCount == 1) {
            return data
        }

        val bundle = pending.getOrPut(key) {
            PendingBundle(header.totalSize, header.fragmentCount)
        }

        bundle.fragments[header.fragmentIndex] = data

        if (bundle.fragments.size >= bundle.fragmentCount) {
            pending.remove(key)
            // Reassemble in order
            val result = ByteArray(bundle.totalSize)
            var offset = 0
            for (i in 0 until bundle.fragmentCount) {
                val frag = bundle.fragments[i] ?: run {
                    Log.w(TAG, "Missing fragment $i for bundle $key")
                    return null
                }
                val len = minOf(frag.size, bundle.totalSize - offset)
                System.arraycopy(frag, 0, result, offset, len)
                offset += len
            }
            Log.d(TAG, "Reassembled bundle $key: ${bundle.fragmentCount} fragments, ${bundle.totalSize} bytes")
            return result
        }

        return null
    }

    /** Remove stale incomplete bundles. */
    fun pruneStale() {
        val cutoff = System.currentTimeMillis() - timeoutMs
        val removed = pending.entries.count { it.value.createdAt < cutoff }
        pending.entries.removeIf { it.value.createdAt < cutoff }
        if (removed > 0) Log.d(TAG, "Pruned $removed stale bundles")
    }

    /** Number of pending incomplete bundles. */
    val pendingCount: Int get() = pending.size

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
