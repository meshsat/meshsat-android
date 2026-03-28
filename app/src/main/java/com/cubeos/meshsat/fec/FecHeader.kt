package com.cubeos.meshsat.fec

/**
 * FEC shard header — 4 bytes prepended to each encoded shard.
 *
 * Wire format:
 *   [0] codec_id:      0x01 = Reed-Solomon
 *   [1] data_shards:   K (number of original data shards)
 *   [2] parity_shards: M (number of parity shards)
 *   [3] shard_index:   0..K+M-1 (this shard's position)
 */
data class FecHeader(
    val codecId: Int = CODEC_REED_SOLOMON,
    val dataShards: Int,
    val parityShards: Int,
    val shardIndex: Int,
) {
    companion object {
        const val CODEC_REED_SOLOMON = 0x01
        const val HEADER_SIZE = 4

        fun unmarshal(data: ByteArray): FecHeader? {
            if (data.size < HEADER_SIZE) return null
            return FecHeader(
                codecId = data[0].toInt() and 0xFF,
                dataShards = data[1].toInt() and 0xFF,
                parityShards = data[2].toInt() and 0xFF,
                shardIndex = data[3].toInt() and 0xFF,
            )
        }
    }

    fun marshal(): ByteArray = byteArrayOf(
        codecId.toByte(),
        dataShards.toByte(),
        parityShards.toByte(),
        shardIndex.toByte(),
    )

    val totalShards: Int get() = dataShards + parityShards
    val isDataShard: Boolean get() = shardIndex < dataShards
    val isParityShard: Boolean get() = shardIndex >= dataShards
}
