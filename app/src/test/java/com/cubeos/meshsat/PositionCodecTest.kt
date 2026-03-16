package com.cubeos.meshsat

import com.cubeos.meshsat.codec.DeltaEncoder
import com.cubeos.meshsat.codec.Position
import com.cubeos.meshsat.codec.PositionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for PositionCodec (Phase D: binary position + delta compression).
 */
class PositionCodecTest {

    @Test
    fun `full position encode-decode round trip`() {
        val pos = Position(
            lat = 47.123456,
            lon = -122.654321,
            alt = 150,
            heading = 270,
            speed = 500,
            battery = 85,
        )

        val encoded = PositionCodec.encode(pos)
        assertEquals(PositionCodec.FULL_POSITION_SIZE, encoded.size)
        assertEquals(PositionCodec.HEADER_FULL.toByte(), encoded[0])

        val decoded = PositionCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(pos.lat, decoded!!.lat, 0.000001)
        assertEquals(pos.lon, decoded.lon, 0.000001)
        assertEquals(pos.alt, decoded.alt)
        assertEquals(pos.heading, decoded.heading)
        assertEquals(pos.speed, decoded.speed)
        assertEquals(pos.battery, decoded.battery)
    }

    @Test
    fun `full position is 16 bytes`() {
        val pos = Position(lat = 0.0, lon = 0.0, alt = 0, heading = 0, speed = 0, battery = 0)
        assertEquals(16, PositionCodec.encode(pos).size)
    }

    @Test
    fun `delta encoder first message is full frame`() {
        val encoder = DeltaEncoder()
        val pos = Position(lat = 47.0, lon = -122.0, alt = 100, heading = 0, speed = 0, battery = 50)
        val encoded = encoder.encodeDelta(pos)
        assertEquals(PositionCodec.HEADER_FULL.toByte(), encoded[0])
        assertEquals(PositionCodec.FULL_POSITION_SIZE, encoded.size)
    }

    @Test
    fun `delta encoder small movement uses delta frame`() {
        val encoder = DeltaEncoder()
        val pos1 = Position(lat = 47.0, lon = -122.0, alt = 100, heading = 0, speed = 0, battery = 50)
        encoder.encodeDelta(pos1) // first = full

        val pos2 = Position(lat = 47.001, lon = -122.001, alt = 101, heading = 10, speed = 100, battery = 49)
        val delta = encoder.encodeDelta(pos2)
        assertEquals(PositionCodec.HEADER_DELTA.toByte(), delta[0])
        assertEquals(PositionCodec.DELTA_POSITION_SIZE, delta.size)
    }

    @Test
    fun `delta encoder large movement falls back to full`() {
        val encoder = DeltaEncoder()
        encoder.encodeDelta(Position(lat = 47.0, lon = -122.0, alt = 100, heading = 0, speed = 0, battery = 50))

        val pos2 = Position(lat = 48.0, lon = -123.0, alt = 5000, heading = 180, speed = 1000, battery = 40)
        val encoded = encoder.encodeDelta(pos2)
        assertEquals(PositionCodec.HEADER_FULL.toByte(), encoded[0])
    }

    @Test
    fun `delta decode round trip`() {
        val encoder = DeltaEncoder()
        val decoder = DeltaEncoder()

        val pos1 = Position(lat = 47.123, lon = -122.456, alt = 200, heading = 90, speed = 300, battery = 75)
        val enc1 = encoder.encodeDelta(pos1)
        val dec1 = decoder.decodeDelta(enc1)
        assertNotNull(dec1)
        assertEquals(pos1.lat, dec1!!.lat, 0.000001)

        val pos2 = Position(lat = 47.124, lon = -122.455, alt = 201, heading = 95, speed = 310, battery = 74)
        val enc2 = encoder.encodeDelta(pos2)
        val dec2 = decoder.decodeDelta(enc2)
        assertNotNull(dec2)
        assertEquals(pos2.lat, dec2!!.lat, 0.001)
    }

    @Test
    fun `reset forces full frame`() {
        val encoder = DeltaEncoder()
        encoder.encodeDelta(Position(lat = 47.0, lon = -122.0, alt = 100, heading = 0, speed = 0, battery = 50))
        encoder.reset()
        val encoded = encoder.encodeDelta(Position(lat = 47.001, lon = -122.001, alt = 100, heading = 0, speed = 0, battery = 50))
        assertEquals(PositionCodec.HEADER_FULL.toByte(), encoded[0])
    }

    @Test
    fun `negative coordinates encode correctly`() {
        val pos = Position(lat = -33.8688, lon = 151.2093, alt = 0, heading = 0, speed = 0, battery = 100)
        val decoded = PositionCodec.decode(PositionCodec.encode(pos))
        assertNotNull(decoded)
        assertEquals(-33.8688, decoded!!.lat, 0.000001)
        assertEquals(151.2093, decoded.lon, 0.000001)
    }
}
