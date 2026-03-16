package com.cubeos.meshsat.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Binary position encoding with delta compression.
 * Port of meshsat/internal/codec/position.go.
 *
 * Full frame: 16 bytes (1B header + 15B payload)
 * Delta frame: 11 bytes (1B header + 10B payload)
 */
object PositionCodec {

    /** Magic prefix for a full position encoding. */
    const val HEADER_FULL: Byte = 0x50

    /** Magic prefix for a delta position encoding. */
    const val HEADER_DELTA: Byte = 0x44

    /** Wire size of a full position frame. */
    const val FULL_POSITION_SIZE = 16

    /** Wire size of a delta position frame. */
    const val DELTA_POSITION_SIZE = 11

    /** Multiplier for lat/lon degree → microdegree conversion. */
    private const val LAT_LON_SCALE = 1_000_000.0

    /** Largest lat/lon delta that fits in an Int16 (±32767 microdegrees ≈ ±0.033°). */
    private const val MAX_DELTA = Short.MAX_VALUE.toInt()

    /**
     * Encode a full position into 16 bytes.
     */
    fun encode(p: Position): ByteArray {
        val buf = ByteArray(FULL_POSITION_SIZE)
        buf[0] = HEADER_FULL
        encodePayload(buf, 1, p)
        return buf
    }

    /**
     * Decode a full position frame. Input must be >= 16 bytes with HEADER_FULL prefix.
     */
    fun decode(data: ByteArray): Position {
        require(data.size >= FULL_POSITION_SIZE) { "codec: position data too short" }
        require(data[0] == HEADER_FULL) { "codec: invalid position header" }
        return decodePayload(data, 1)
    }

    /**
     * Write 15 bytes of position payload starting at [offset].
     */
    internal fun encodePayload(buf: ByteArray, offset: Int, p: Position) {
        val bb = ByteBuffer.wrap(buf, offset, 15).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt((p.lat * LAT_LON_SCALE).roundToInt())
        bb.putInt((p.lon * LAT_LON_SCALE).roundToInt())
        bb.putShort(p.alt)
        bb.putShort(p.heading.toShort())
        bb.putShort(p.speed.toShort())
        buf[offset + 14] = p.battery.toByte()
    }

    /**
     * Read 15 bytes of position payload starting at [offset].
     */
    internal fun decodePayload(data: ByteArray, offset: Int): Position {
        val bb = ByteBuffer.wrap(data, offset, 15).order(ByteOrder.LITTLE_ENDIAN)
        val lat = bb.getInt()
        val lon = bb.getInt()
        return Position(
            lat = lat.toDouble() / LAT_LON_SCALE,
            lon = lon.toDouble() / LAT_LON_SCALE,
            alt = bb.getShort(),
            heading = bb.getShort().toInt() and 0xFFFF,
            speed = bb.getShort().toInt() and 0xFFFF,
            battery = data[offset + 14].toInt() and 0xFF,
        )
    }
}

/**
 * GPS fix with auxiliary telemetry.
 * Port of meshsat/internal/codec.Position.
 */
data class Position(
    val lat: Double,        // degrees
    val lon: Double,        // degrees
    val alt: Short = 0,     // meters
    val heading: Int = 0,   // degrees 0-359 (UInt16 range)
    val speed: Int = 0,     // cm/s (UInt16 range)
    val battery: Int = 0,   // percent 0-100
)

/**
 * Stateful encoder that compresses sequential positions using delta encoding.
 * Small movements produce an 11-byte delta frame instead of a 16-byte full frame.
 * Port of meshsat/internal/codec.DeltaEncoder.
 */
class DeltaEncoder {

    private var last: Position? = null

    /**
     * Encode a position, using delta compression when possible.
     * First position or large movements produce a full 16-byte frame.
     * Small movements produce an 11-byte delta frame.
     */
    fun encodeDelta(p: Position): ByteArray {
        val prev = last
        if (prev == null) {
            last = p
            return PositionCodec.encode(p)
        }

        val scale = 1_000_000.0
        val dlat = (p.lat * scale).roundToLong() - (prev.lat * scale).roundToLong()
        val dlon = (p.lon * scale).roundToLong() - (prev.lon * scale).roundToLong()
        val dalt = p.alt.toInt() - prev.alt.toInt()

        if (dlat < -PositionCodec.run { Short.MAX_VALUE.toInt() } || dlat > Short.MAX_VALUE ||
            dlon < -Short.MAX_VALUE || dlon > Short.MAX_VALUE ||
            dalt < Byte.MIN_VALUE || dalt > Byte.MAX_VALUE
        ) {
            last = p
            return PositionCodec.encode(p)
        }

        val buf = ByteArray(PositionCodec.DELTA_POSITION_SIZE)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        buf[0] = PositionCodec.HEADER_DELTA
        bb.position(1)
        bb.putShort(dlat.toInt().toShort())
        bb.putShort(dlon.toInt().toShort())
        buf[5] = dalt.toByte()
        bb.position(6)
        bb.putShort(p.heading.toShort())
        bb.putShort(p.speed.toShort())
        buf[10] = p.battery.toByte()

        last = p
        return buf
    }

    /**
     * Decode either a full or delta position frame. Auto-detects from header byte.
     */
    fun decodeDelta(data: ByteArray): Position {
        require(data.isNotEmpty()) { "codec: empty data" }

        return when (data[0]) {
            PositionCodec.HEADER_FULL -> {
                val p = PositionCodec.decode(data)
                last = p
                p
            }

            PositionCodec.HEADER_DELTA -> {
                require(data.size >= PositionCodec.DELTA_POSITION_SIZE) { "codec: delta data too short" }
                val prev = requireNotNull(last) { "codec: delta without prior full position" }

                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(1)
                val dlat = bb.getShort().toInt()
                val dlon = bb.getShort().toInt()
                val dalt = data[5].toInt()  // signed byte

                val scale = 1_000_000.0
                val lastLatMicro = (prev.lat * scale).roundToLong()
                val lastLonMicro = (prev.lon * scale).roundToLong()

                val p = Position(
                    lat = (lastLatMicro + dlat).toDouble() / scale,
                    lon = (lastLonMicro + dlon).toDouble() / scale,
                    alt = (prev.alt + dalt).toShort(),
                    heading = bb.getShort().toInt() and 0xFFFF,
                    speed = bb.getShort().toInt() and 0xFFFF,
                    battery = data[10].toInt() and 0xFF,
                )
                last = p
                p
            }

            else -> throw IllegalArgumentException("codec: unknown header byte 0x${data[0].toString(16)}")
        }
    }

    /** Clear last position, forcing next encode to produce a full frame. */
    fun reset() {
        last = null
    }
}
