package net.meshsat.android.rns

/** One row of the path table (Transport.path_table). */
class PathEntry(
    val destHash: ByteArray,
    var timestampMs: Long,          // last use
    val nextHop: ByteArray,         // transport id of the next hop (== dest when direct)
    val hops: Int,
    var expiresMs: Long,
    val randomBlobs: MutableList<ByteArray>,   // the 10-byte random hashes heard, newest last
    val iface: String,              // received on / next-hop interface
    val announce: Announce,
    val packetHash: ByteArray,
    var unresponsive: Boolean = false,
) {
    val direct: Boolean get() = hops <= 1

    /** The newest emission timestamp among the random blobs. */
    fun emittedAt(): Long = randomBlobs.maxOfOrNull { Announce.blobTimestamp(it) } ?: 0L
}

/** Known paths; expired entries stay one extra TTL so a worse path can replace them (as RNS keeps candidates). */
class PathTable(val ttlMs: Long, private val now: () -> Long) {
    private val lock = Any()
    private val m = HashMap<String, PathEntry>()

    fun get(dest: ByteArray): PathEntry? = synchronized(lock) { m[dest.hex()] }

    fun has(dest: ByteArray): Boolean { val e = get(dest) ?: return false; return now() < e.expiresMs }

    fun hops(dest: ByteArray): Int = get(dest)?.hops ?: -1

    fun put(e: PathEntry) = synchronized(lock) { m[e.destHash.hex()] = e }

    fun remove(dest: ByteArray) = synchronized(lock) { m.remove(dest.hex()) }

    fun expire(nowMs: Long): Int = synchronized(lock) {
        val it = m.entries.iterator()
        var removed = 0
        while (it.hasNext()) { if (nowMs > it.next().value.expiresMs + ttlMs) { it.remove(); removed++ } }
        removed
    }

    fun all(): List<PathEntry> = synchronized(lock) { m.values.toList() }

    fun count(): Int = synchronized(lock) { m.size }
}
