package net.meshsat.android.rns

import java.io.ByteArrayOutputStream

/**
 * A msgpack codec byte-identical to RNS.vendor.umsgpack for the value shapes
 * Reticulum and LXMF use. LXMF hashes the RE-ENCODING of a decoded payload, so the
 * encoder must write exactly what umsgpack writes: positive fixint / uint8..64 for
 * non-negative integers, negative fixint / int8..64 for negative ones, float64 for
 * every float, str for text, bin for bytes, maps in insertion order.
 */
sealed class MpValue

object MpNil : MpValue() { override fun toString() = "nil" }
data class MpBool(val value: Boolean) : MpValue()
/** [uint64] marks a value decoded from a uint64 above Long.MAX_VALUE; it re-encodes as that. */
data class MpInt(val value: Long, val uint64: Boolean = false) : MpValue()
data class MpFloat(val value: Double) : MpValue()
data class MpStr(val value: String) : MpValue()
class MpBin(val value: ByteArray) : MpValue() {
    override fun equals(other: Any?) = other is MpBin && value.contentEquals(other.value)
    override fun hashCode() = value.contentHashCode()
    override fun toString() = "bin(${value.size})"
}
data class MpArray(val items: List<MpValue>) : MpValue() {
    constructor(vararg items: MpValue) : this(items.toList())
}
/** Insertion-ordered. */
data class MpMap(val entries: List<Pair<MpValue, MpValue>>) : MpValue() {
    constructor(vararg entries: Pair<MpValue, MpValue>) : this(entries.toList())

    operator fun get(key: Long): MpValue? = entries.firstOrNull { (k, _) -> k is MpInt && k.value == key }?.second
    operator fun get(key: String): MpValue? = entries.firstOrNull { (k, _) -> k is MpStr && k.value == key }?.second
}
/** An ext value, kept as its raw bytes and re-emitted verbatim. */
class MpExt(val raw: ByteArray) : MpValue() {
    override fun equals(other: Any?) = other is MpExt && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
}

fun MpValue.asLong(): Long? = (this as? MpInt)?.takeIf { !it.uint64 }?.value
fun MpValue.asBytes(): ByteArray? = when (this) { is MpBin -> value; is MpStr -> value.toByteArray(Charsets.UTF_8); else -> null }

class MsgpackException(message: String) : Exception(message)

object Msgpack {
    fun pack(v: MpValue): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, v)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) write(((v shr s) and 0xFF).toInt()) }
    private fun ByteArrayOutputStream.u64(v: Long) { for (s in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) write(((v shr s) and 0xFF).toInt()) }

    private fun write(out: ByteArrayOutputStream, v: MpValue) {
        when (v) {
            is MpNil -> out.write(0xC0)
            is MpBool -> out.write(if (v.value) 0xC3 else 0xC2)
            is MpInt -> {
                val i = v.value
                if (v.uint64 || i >= 0) {
                    when {
                        !v.uint64 && i < 128 -> out.write(i.toInt())
                        !v.uint64 && i < (1L shl 8) -> { out.write(0xCC); out.write(i.toInt()) }
                        !v.uint64 && i < (1L shl 16) -> { out.write(0xCD); out.u16(i.toInt()) }
                        !v.uint64 && i < (1L shl 32) -> { out.write(0xCE); out.u32(i) }
                        else -> { out.write(0xCF); out.u64(i) }
                    }
                } else {
                    when {
                        i >= -32 -> out.write(i.toInt() and 0xFF)
                        i >= Byte.MIN_VALUE -> { out.write(0xD0); out.write(i.toInt() and 0xFF) }
                        i >= Short.MIN_VALUE -> { out.write(0xD1); out.u16(i.toInt() and 0xFFFF) }
                        i >= Int.MIN_VALUE -> { out.write(0xD2); out.u32(i and 0xFFFFFFFFL) }
                        else -> { out.write(0xD3); out.u64(i) }
                    }
                }
            }
            is MpFloat -> { out.write(0xCB); out.u64(java.lang.Double.doubleToLongBits(v.value)) }
            is MpStr -> {
                val b = v.value.toByteArray(Charsets.UTF_8)
                when {
                    b.size < 32 -> out.write(0xA0 or b.size)
                    b.size < (1 shl 8) -> { out.write(0xD9); out.write(b.size) }
                    b.size < (1 shl 16) -> { out.write(0xDA); out.u16(b.size) }
                    else -> { out.write(0xDB); out.u32(b.size.toLong()) }
                }
                out.write(b)
            }
            is MpBin -> {
                val b = v.value
                when {
                    b.size < (1 shl 8) -> { out.write(0xC4); out.write(b.size) }
                    b.size < (1 shl 16) -> { out.write(0xC5); out.u16(b.size) }
                    else -> { out.write(0xC6); out.u32(b.size.toLong()) }
                }
                out.write(b)
            }
            is MpArray -> {
                val n = v.items.size
                when {
                    n < 16 -> out.write(0x90 or n)
                    n < (1 shl 16) -> { out.write(0xDC); out.u16(n) }
                    else -> { out.write(0xDD); out.u32(n.toLong()) }
                }
                for (e in v.items) write(out, e)
            }
            is MpMap -> {
                val n = v.entries.size
                when {
                    n < 16 -> out.write(0x80 or n)
                    n < (1 shl 16) -> { out.write(0xDE); out.u16(n) }
                    else -> { out.write(0xDF); out.u32(n.toLong()) }
                }
                for ((k, e) in v.entries) { write(out, k); write(out, e) }
            }
            is MpExt -> out.write(v.raw)
        }
    }

    /** Decodes one value; returns it with the number of bytes consumed. */
    fun unpack(b: ByteArray, offset: Int = 0): Pair<MpValue, Int> {
        val d = Decoder(b, offset)
        val v = d.value()
        return v to (d.pos - offset)
    }

    private class Decoder(val b: ByteArray, var pos: Int) {
        fun need(n: Int): Int {
            if (n < 0 || pos + n > b.size) throw MsgpackException("truncated msgpack")
            val start = pos; pos += n; return start
        }
        fun u8(): Int { val s = need(1); return b[s].toInt() and 0xFF }
        fun uint(n: Int): Long { val s = need(n); var u = 0L; for (i in 0 until n) u = (u shl 8) or (b[s + i].toLong() and 0xFF); return u }
        fun length(sizeExp: Int): Int { val u = uint(1 shl sizeExp); if (u > (1L shl 31)) throw MsgpackException("length too large"); return u.toInt() }

        fun value(): MpValue {
            val h = u8()
            when {
                h <= 0x7F -> return MpInt(h.toLong())
                h >= 0xE0 -> return MpInt((h.toByte()).toLong())
                (h and 0xE0) == 0xA0 -> { val n = h and 0x1F; val s = need(n); return MpStr(String(b, s, n, Charsets.UTF_8)) }
                (h and 0xF0) == 0x90 -> return array(h and 0x0F)
                (h and 0xF0) == 0x80 -> return map(h and 0x0F)
            }
            return when (h) {
                0xC0 -> MpNil
                0xC2 -> MpBool(false)
                0xC3 -> MpBool(true)
                0xC4, 0xC5, 0xC6 -> { val n = length(h - 0xC4); val s = need(n); MpBin(b.copyOfRange(s, s + n)) }
                0xCA -> MpFloat(java.lang.Float.intBitsToFloat(uint(4).toInt()).toDouble())
                0xCB -> MpFloat(java.lang.Double.longBitsToDouble(uint(8)))
                0xCC, 0xCD, 0xCE -> MpInt(uint(1 shl (h - 0xCC)))
                0xCF -> { val u = uint(8); MpInt(u, uint64 = u < 0) }
                0xD0, 0xD1, 0xD2, 0xD3 -> {
                    val n = 1 shl (h - 0xD0)
                    val u = uint(n)
                    val shift = 64 - 8 * n
                    MpInt((u shl shift) shr shift)
                }
                0xD9, 0xDA, 0xDB -> { val n = length(h - 0xD9); val s = need(n); MpStr(String(b, s, n, Charsets.UTF_8)) }
                0xDC, 0xDD -> array(length(h - 0xDC + 1))
                0xDE, 0xDF -> map(length(h - 0xDE + 1))
                0xD4, 0xD5, 0xD6, 0xD7, 0xD8 -> { val n = 1 shl (h - 0xD4); val start = pos - 1; need(1 + n); MpExt(b.copyOfRange(start, pos)) }
                0xC7, 0xC8, 0xC9 -> { val start = pos - 1; val n = length(h - 0xC7); need(1 + n); MpExt(b.copyOfRange(start, pos)) }
                else -> throw MsgpackException("unsupported type byte 0x%02x".format(h))
            }
        }

        fun array(n: Int): MpArray {
            val items = ArrayList<MpValue>(minOf(n, 1024))
            repeat(n) { items.add(value()) }
            return MpArray(items)
        }

        fun map(n: Int): MpMap {
            val entries = ArrayList<Pair<MpValue, MpValue>>(minOf(n, 1024))
            repeat(n) { val k = value(); val v = value(); entries.add(k to v) }
            return MpMap(entries)
        }
    }
}
