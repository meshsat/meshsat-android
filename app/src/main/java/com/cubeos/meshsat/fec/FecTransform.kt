package com.cubeos.meshsat.fec

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * FEC transform for the TransformPipeline.
 *
 * Egress: splits payload into K+M FEC-encoded shards.
 * Ingress: collects shards and reconstructs when K arrive.
 *
 * Per-interface configuration via transform params:
 *   {"type":"fec","params":{"data_shards":"5","parity_shards":"2"}}
 */
class FecTransform {
    companion object {
        private const val TAG = "FecTransform"
    }

    /**
     * Encode a payload into FEC shards.
     *
     * @param data Original payload
     * @param dataShards K
     * @param parityShards M
     * @return List of K+M shard byte arrays (each with FecHeader)
     */
    fun encodeToShards(data: ByteArray, dataShards: Int, parityShards: Int): List<ByteArray> {
        return ReedSolomon.encode(data, dataShards, parityShards)
    }

    /**
     * Shard collector for ingress — accumulates shards by group key
     * and decodes when enough arrive.
     */
    private val collectors = ConcurrentHashMap<String, ShardCollector>()

    data class ShardCollector(
        val originalSize: Int,
        val shards: MutableList<ByteArray> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis(),
    )

    /**
     * Feed a received shard. Returns the decoded payload if K shards
     * have been collected, or null if more shards are needed.
     *
     * @param groupKey Unique identifier for this FEC group (e.g., delivery msgRef)
     * @param shard The received shard bytes (with FecHeader)
     * @param originalSize Original payload size for correct trimming
     * @return Decoded payload, or null if insufficient shards
     */
    fun feedShard(groupKey: String, shard: ByteArray, originalSize: Int): ByteArray? {
        val header = FecHeader.unmarshal(shard) ?: return null
        val collector = collectors.getOrPut(groupKey) { ShardCollector(originalSize) }
        collector.shards.add(shard)

        if (collector.shards.size >= header.dataShards) {
            collectors.remove(groupKey)
            val result = ReedSolomon.decode(collector.shards, originalSize)
            if (result != null) {
                Log.d(TAG, "FEC decode success: $groupKey (${collector.shards.size}/${header.totalShards} shards)")
            } else {
                Log.w(TAG, "FEC decode failed: $groupKey")
            }
            return result
        }
        return null
    }

    /** Remove stale collectors (older than 5 minutes). */
    fun pruneStale() {
        val cutoff = System.currentTimeMillis() - 300_000
        collectors.entries.removeIf { it.value.createdAt < cutoff }
    }
}
