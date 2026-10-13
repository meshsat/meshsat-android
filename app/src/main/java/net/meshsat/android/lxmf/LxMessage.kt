package net.meshsat.android.lxmf

import net.meshsat.android.rns.LinkWire
import net.meshsat.android.rns.MpArray
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpFloat
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.MpStr
import net.meshsat.android.rns.MpValue
import net.meshsat.android.rns.Msgpack
import net.meshsat.android.rns.MsgpackException
import net.meshsat.android.rns.Rns
import net.meshsat.android.rns.RnsIdentity
import net.meshsat.android.rns.asBytes
import net.meshsat.android.rns.hex

/** How a message travels (LXMessage.OPPORTUNISTIC / DIRECT / PROPAGATED). */
enum class LxMethod(val code: Int) {
    OPPORTUNISTIC(1), DIRECT(2), PROPAGATED(3);
    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
}

/** LXMessage.state. */
enum class LxState(val code: Int) {
    GENERATING(0), OUTBOUND(1), SENDING(2), SENT(4), DELIVERED(8), REJECTED(253), CANCELLED(254), FAILED(255);
    val terminal: Boolean get() = this == DELIVERED || this == REJECTED || this == CANCELLED || this == FAILED
}

/**
 * One LXMF message (LXMF/LXMessage.py, 1.2.0): packed form
 * dest (16) || source (16) || signature (64) || msgpack([timestamp, title, content, fields, [stamp]]).
 * The hash is SHA-256(dest || source || msgpack([timestamp, title, content, fields])), the
 * signature covers that hashed part plus the hash. [MESHSAT-1553]
 */
class LxMessage(
    val dest: ByteArray,
    val source: ByteArray,
    var timestamp: Double = 0.0,
    var title: ByteArray = ByteArray(0),
    var content: ByteArray = ByteArray(0),
    /** Fields by LXMF field number; unknown ones (Sideband telemetry, icons, tickets) are kept as they came. */
    var fields: MpMap = MpMap(emptyList()),
) {
    var stamp: ByteArray? = null
    var hash: ByteArray = ByteArray(0)
    var signature: ByteArray = ByteArray(0)
    var packed: ByteArray = ByteArray(0)

    // Set on inbound
    var signatureValid = false
    var sourceKnown = false
    var stampValid = false
    var stampChecked = false
    var stampValue = 0
    var method: LxMethod? = null
    var iface: String = ""
    var receivedAtMs: Long = 0

    val hashHex: String get() = hash.hex()
    val destHex: String get() = dest.hex()
    val sourceHex: String get() = source.hex()
    val titleString: String get() = String(title, Charsets.UTF_8)
    val contentString: String get() = String(content, Charsets.UTF_8)

    private fun payloadValue(withStamp: Boolean): MpArray {
        val items = arrayListOf<MpValue>(MpFloat(timestamp), MpBin(title), MpBin(content), fields)
        val s = stamp
        if (withStamp && s != null) items.add(MpBin(s))
        return MpArray(items)
    }

    private fun payloadBytes(withStamp: Boolean): ByteArray = Msgpack.pack(payloadValue(withStamp))

    /** What the method choice is judged on: the packed payload minus timestamp and structure overhead (LXMessage.pack content_size). */
    fun contentSize(): Int = payloadBytes(withStamp = false).size - TIMESTAMP_SIZE - STRUCT_OVERHEAD

    /** The size of the packed message as it travels (with the stamp). */
    val packedSize: Int get() = packed.size

    /**
     * Computes the hash, generates a stamp when [stampCost] > 0, signs with the
     * source identity and produces the packed form (LXMessage.pack).
     * [stamper] returns null when it could not produce one in time.
     */
    fun pack(sourceIdentity: RnsIdentity, stampCost: Int = 0, stamper: ((hash: ByteArray, cost: Int) -> ByteArray?)? = null) {
        if (timestamp == 0.0) timestamp = System.currentTimeMillis() / 1000.0
        val payload = payloadBytes(withStamp = false)
        val hashed = dest + source + payload
        hash = Rns.sha256(hashed)
        if (stampCost > 0 && stamper != null) {
            stamp = stamper(hash, stampCost) ?: throw IllegalStateException("stamp generation failed")
        }
        signature = sourceIdentity.sign(hashed + hash)
        signatureValid = true
        packed = dest + source + signature + payloadBytes(withStamp = true)
    }

    override fun toString(): String = "<LXMessage ${hash.copyOf(8).hex()}>"

    companion object {
        const val APP_NAME = "lxmf"
        const val DELIVERY_NAME = "lxmf.delivery"
        const val PROPAGATION_NAME = "lxmf.propagation"
        const val DEST_LEN = 16
        const val SIG_LEN = 64
        const val TIMESTAMP_SIZE = 8
        const val STRUCT_OVERHEAD = 8
        const val OVERHEAD = 2 * DEST_LEN + SIG_LEN + TIMESTAMP_SIZE + STRUCT_OVERHEAD   // 112
        /** Opportunistic single packet: ENCRYPTED_MDU - overhead + dest = 287 at MTU 500 (LXMF 1.2.0; 1.1.0 allowed 8 more). */
        const val ENCRYPTED_PACKET_MAX_CONTENT = Rns.ENCRYPTED_MDU - OVERHEAD + DEST_LEN
        /** A link packet carries 431 - overhead = 319 bytes of content at MTU 500. */
        val LINK_PACKET_MAX_CONTENT = LinkWire.mdu(Rns.MTU) - OVERHEAD

        // Field numbers (LXMF/LXMF.py)
        const val FIELD_EMBEDDED_LXMS = 0x01L
        const val FIELD_TELEMETRY = 0x02L
        const val FIELD_TELEMETRY_STREAM = 0x03L
        const val FIELD_ICON_APPEARANCE = 0x04L
        const val FIELD_FILE_ATTACHMENTS = 0x05L
        const val FIELD_IMAGE = 0x06L
        const val FIELD_AUDIO = 0x07L
        const val FIELD_THREAD = 0x08L
        const val FIELD_COMMANDS = 0x09L
        const val FIELD_RESULTS = 0x0AL
        const val FIELD_GROUP = 0x0BL
        const val FIELD_TICKET = 0x0CL
        const val FIELD_EVENT = 0x0DL
        const val FIELD_RNR_REFS = 0x0EL
        const val FIELD_RENDERER = 0x0FL

        /**
         * Parses a packed message (LXMessage.unpack_from_bytes). The signature is
         * verified when [sigPubOf] knows the source; a stamp, when present, is kept.
         * Throws [LxmfException] on malformed input; unknown fields never fail.
         */
        fun unpack(packed: ByteArray, sigPubOf: ((source: ByteArray) -> ByteArray?)? = null): LxMessage {
            if (packed.size < 2 * DEST_LEN + SIG_LEN + 1) throw LxmfException("packed message too short")
            val dest = packed.copyOfRange(0, DEST_LEN)
            val source = packed.copyOfRange(DEST_LEN, 2 * DEST_LEN)
            val sig = packed.copyOfRange(2 * DEST_LEN, 2 * DEST_LEN + SIG_LEN)
            val payloadRaw = packed.copyOfRange(2 * DEST_LEN + SIG_LEN, packed.size)
            val (v, n) = try { Msgpack.unpack(payloadRaw) } catch (e: MsgpackException) { throw LxmfException("payload: ${e.message}") }
            val arr = v as? MpArray ?: throw LxmfException("payload is not an array")
            if (n != payloadRaw.size || arr.items.size < 4) throw LxmfException("payload is not a 4-element array")
            val m = LxMessage(dest, source)
            if (arr.items.size > 4) (arr.items[4] as? MpBin)?.let { m.stamp = it.value }
            m.timestamp = when (val t = arr.items[0]) { is MpFloat -> t.value; is MpInt -> t.value.toDouble(); else -> 0.0 }
            m.title = arr.items[1].asBytes() ?: ByteArray(0)
            m.content = arr.items[2].asBytes() ?: ByteArray(0)
            m.fields = arr.items[3] as? MpMap ?: MpMap(emptyList())
            // The hash is over the re-encoding of the first four elements.
            val payload = Msgpack.pack(MpArray(arr.items[0], arr.items[1], arr.items[2], arr.items[3]))
            val hashed = dest + source + payload
            m.hash = Rns.sha256(hashed)
            m.signature = sig
            m.packed = packed.copyOf()
            if (sigPubOf != null) {
                val pub = sigPubOf(source)
                if (pub != null) {
                    m.sourceKnown = true
                    m.signatureValid = RnsIdentity.verifySignature(pub, hashed + m.hash, sig)
                }
            }
            return m
        }

        /** A fields map from string content, convenient for callers. */
        fun fieldsOf(vararg entries: Pair<Long, MpValue>): MpMap = MpMap(entries.map { (k, v) -> MpInt(k) to v })
    }
}

class LxmfException(message: String) : Exception(message)

/** The textual form of a field value, for display; binary values show their size. */
fun MpValue.displayString(): String = when (this) {
    is MpStr -> value
    is MpBin -> "(${value.size} bytes)"
    is MpInt -> value.toString()
    is MpFloat -> value.toString()
    else -> toString()
}
