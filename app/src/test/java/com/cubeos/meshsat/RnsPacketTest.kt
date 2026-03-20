package com.cubeos.meshsat

import com.cubeos.meshsat.reticulum.FlagFields
import com.cubeos.meshsat.reticulum.RnsConstants
import com.cubeos.meshsat.reticulum.RnsDestination
import com.cubeos.meshsat.reticulum.RnsPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Reticulum wire-compatible packet format (MESHSAT-208).
 */
class RnsPacketTest {

    // --- Flag encoding/decoding ---

    @Test
    fun `flags encode and decode round-trip`() {
        // HEADER_1, no context, BROADCAST, SINGLE, DATA
        val packet = RnsPacket.data(
            destHash = ByteArray(16),
            payload = ByteArray(0),
            destType = RnsConstants.DEST_SINGLE,
        )
        val flags = packet.encodeFlags()
        // header_type=0, context=0, propagation=0, dest=0, type=0
        assertEquals(0x00, flags)

        val decoded = RnsPacket.decodeFlags(flags)
        assertEquals(RnsConstants.HEADER_1, decoded.headerType)
        assertFalse(decoded.contextFlag)
        assertEquals(RnsConstants.PROPAGATION_BROADCAST, decoded.propagationType)
        assertEquals(RnsConstants.DEST_SINGLE, decoded.destType)
        assertEquals(RnsConstants.PACKET_DATA, decoded.packetType)
    }

    @Test
    fun `flags encode announce packet correctly`() {
        val packet = RnsPacket.announce(
            destHash = ByteArray(16),
            announceData = ByteArray(0),
        )
        val flags = packet.encodeFlags()
        // header_type=0, context=0, propagation=0, dest_type=SINGLE(0), packet_type=ANNOUNCE(1)
        assertEquals(0x01, flags)
    }

    @Test
    fun `flags encode link request correctly`() {
        val packet = RnsPacket.linkRequest(
            destHash = ByteArray(16),
            requestData = ByteArray(0),
        )
        val flags = packet.encodeFlags()
        // packet_type=LINKREQUEST(2)
        assertEquals(0x02, flags)
    }

    @Test
    fun `flags encode proof correctly`() {
        val packet = RnsPacket.proof(
            destHash = ByteArray(16),
            proofData = ByteArray(0),
        )
        val flags = packet.encodeFlags()
        // packet_type=PROOF(3)
        assertEquals(0x03, flags)
    }

    @Test
    fun `flags encode LINK dest type correctly`() {
        val packet = RnsPacket.data(
            destHash = ByteArray(16),
            payload = ByteArray(0),
            destType = RnsConstants.DEST_LINK,
        )
        val flags = packet.encodeFlags()
        // dest_type=LINK(3) at bits [3:2] = 0x0C
        assertEquals(0x0C, flags)
    }

    @Test
    fun `flags encode context flag correctly`() {
        val packet = RnsPacket.data(
            destHash = ByteArray(16),
            payload = ByteArray(0),
            context = RnsConstants.CTX_CHANNEL,
        )
        val flags = packet.encodeFlags()
        // context_flag=1 at bit 5 = 0x20
        assertEquals(0x20, flags)
    }

    @Test
    fun `flags encode transport propagation correctly`() {
        val packet = RnsPacket.data(
            destHash = ByteArray(16),
            payload = ByteArray(0),
        )
        val transport = RnsPacket.wrapForTransport(packet, ByteArray(16))
        val flags = transport.encodeFlags()
        // header_type=HEADER_2(1) at [7:6] = 0x40, propagation=TRANSPORT(1) at [4] = 0x10
        assertEquals(0x50, flags)
    }

    @Test
    fun `all flag bits set correctly`() {
        // HEADER_2 + context + TRANSPORT + LINK + PROOF
        val flags = RnsPacket.decodeFlags(0xFF)
        assertEquals(0x03, flags.headerType)
        assertTrue(flags.contextFlag)
        assertEquals(1, flags.propagationType)
        assertEquals(0x03, flags.destType)
        assertEquals(0x03, flags.packetType)
    }

    // --- Packet marshal/unmarshal ---

    @Test
    fun `HEADER_1 data packet round-trip`() {
        val destHash = ByteArray(16) { (it + 1).toByte() }
        val payload = "hello reticulum".toByteArray()

        val packet = RnsPacket.data(destHash, payload)
        val wire = packet.marshal()

        // Expected size: flags(1) + hops(1) + dest(16) + data(15) = 33
        assertEquals(33, wire.size)
        assertEquals(0x00, wire[0].toInt()) // flags
        assertEquals(0x00, wire[1].toInt()) // hops

        val parsed = RnsPacket.unmarshal(wire)
        assertEquals(RnsConstants.HEADER_1, parsed.headerType)
        assertEquals(RnsConstants.PACKET_DATA, parsed.packetType)
        assertEquals(RnsConstants.DEST_SINGLE, parsed.destType)
        assertEquals(0, parsed.hops)
        assertNull(parsed.transportId)
        assertArrayEquals(destHash, parsed.destHash)
        assertArrayEquals(payload, parsed.data)
    }

    @Test
    fun `HEADER_1 with context byte round-trip`() {
        val destHash = ByteArray(16) { 0xAA.toByte() }
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        val packet = RnsPacket.data(destHash, payload, context = RnsConstants.CTX_KEEPALIVE)
        val wire = packet.marshal()

        // flags(1) + hops(1) + dest(16) + context(1) + data(3) = 22
        assertEquals(22, wire.size)

        val parsed = RnsPacket.unmarshal(wire)
        assertTrue(parsed.contextFlag)
        assertEquals(RnsConstants.CTX_KEEPALIVE, parsed.context)
        assertArrayEquals(payload, parsed.data)
    }

    @Test
    fun `HEADER_2 transport packet round-trip`() {
        val destHash = ByteArray(16) { it.toByte() }
        val transportId = ByteArray(16) { (0xFF - it).toByte() }
        val payload = "routed data".toByteArray()

        val packet = RnsPacket.data(destHash, payload)
        val transport = RnsPacket.wrapForTransport(packet, transportId)
        val wire = transport.marshal()

        // flags(1) + hops(1) + transport_id(16) + dest(16) + data(11) = 45
        assertEquals(45, wire.size)

        val parsed = RnsPacket.unmarshal(wire)
        assertEquals(RnsConstants.HEADER_2, parsed.headerType)
        assertEquals(RnsConstants.PROPAGATION_TRANSPORT, parsed.propagationType)
        assertArrayEquals(transportId, parsed.transportId)
        assertArrayEquals(destHash, parsed.destHash)
        assertArrayEquals(payload, parsed.data)
    }

    @Test
    fun `announce packet round-trip`() {
        val destHash = ByteArray(16) { (it * 3).toByte() }
        val announceData = ByteArray(148) { it.toByte() } // min announce data size (64+10+10+64)

        val packet = RnsPacket.announce(destHash, announceData)
        assertEquals(0, packet.hops)

        val wire = packet.marshal()
        val parsed = RnsPacket.unmarshal(wire)

        assertEquals(RnsConstants.PACKET_ANNOUNCE, parsed.packetType)
        assertEquals(RnsConstants.DEST_SINGLE, parsed.destType)
        assertArrayEquals(destHash, parsed.destHash)
        assertArrayEquals(announceData, parsed.data)
    }

    @Test
    fun `hop count preserved through marshal`() {
        val packet = RnsPacket.data(ByteArray(16), byteArrayOf(0x42))
        val modified = packet.copy(hops = 7)
        val wire = modified.marshal()
        val parsed = RnsPacket.unmarshal(wire)
        assertEquals(7, parsed.hops)
    }

    @Test
    fun `hop count 255 preserved`() {
        val packet = RnsPacket.data(ByteArray(16), byteArrayOf(0x42))
        val modified = packet.copy(hops = 255)
        val wire = modified.marshal()
        val parsed = RnsPacket.unmarshal(wire)
        assertEquals(255, parsed.hops)
    }

    @Test
    fun `empty payload round-trip`() {
        val packet = RnsPacket.data(ByteArray(16), ByteArray(0))
        val wire = packet.marshal()
        val parsed = RnsPacket.unmarshal(wire)
        assertEquals(0, parsed.data.size)
    }

    @Test
    fun `max MDU payload fits in MTU`() {
        val payload = ByteArray(RnsConstants.MDU) // 464 bytes
        val packet = RnsPacket.data(ByteArray(16), payload)
        // HEADER_1 with no context: 2 + 16 + 464 = 482 ≤ 500
        assertTrue(RnsPacket.validateSize(packet))
    }

    @Test
    fun `oversized payload exceeds MTU`() {
        val payload = ByteArray(RnsConstants.MTU) // 500 bytes of data alone
        val packet = RnsPacket.data(ByteArray(16), payload)
        assertFalse(RnsPacket.validateSize(packet))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unmarshal rejects too-short packet`() {
        RnsPacket.unmarshal(ByteArray(5))
    }

    // --- Wire size ---

    @Test
    fun `wire size calculation correct for HEADER_1`() {
        val packet = RnsPacket.data(ByteArray(16), ByteArray(10))
        // 2 + 16 + 10 = 28
        assertEquals(28, packet.wireSize())
    }

    @Test
    fun `wire size calculation correct for HEADER_1 with context`() {
        val packet = RnsPacket.data(ByteArray(16), ByteArray(10), context = RnsConstants.CTX_RESOURCE)
        // 2 + 16 + 1 + 10 = 29
        assertEquals(29, packet.wireSize())
    }

    @Test
    fun `wire size calculation correct for HEADER_2`() {
        val packet = RnsPacket.data(ByteArray(16), ByteArray(10))
        val transport = RnsPacket.wrapForTransport(packet, ByteArray(16))
        // 2 + 16 + 16 + 10 = 44
        assertEquals(44, transport.wireSize())
    }

    // --- Destination hash ---

    @Test
    fun `destination hash is 16 bytes`() {
        val encPub = ByteArray(32) { 0x01 }
        val sigPub = ByteArray(32) { 0x02 }
        val hash = RnsDestination.computeDestHash(encPub, sigPub)
        assertEquals(16, hash.size)
    }

    @Test
    fun `destination hash deterministic`() {
        val encPub = ByteArray(32) { it.toByte() }
        val sigPub = ByteArray(32) { (it + 32).toByte() }
        val hash1 = RnsDestination.computeDestHash(encPub, sigPub)
        val hash2 = RnsDestination.computeDestHash(encPub, sigPub)
        assertArrayEquals(hash1, hash2)
    }

    @Test
    fun `different keys produce different hashes`() {
        val encPub1 = ByteArray(32) { 0x01 }
        val sigPub1 = ByteArray(32) { 0x02 }
        val encPub2 = ByteArray(32) { 0x03 }
        val sigPub2 = ByteArray(32) { 0x04 }
        val hash1 = RnsDestination.computeDestHash(encPub1, sigPub1)
        val hash2 = RnsDestination.computeDestHash(encPub2, sigPub2)
        assertFalse(hash1.contentEquals(hash2))
    }

    @Test
    fun `different app names produce different hashes`() {
        val encPub = ByteArray(32) { 0x01 }
        val sigPub = ByteArray(32) { 0x02 }
        val hash1 = RnsDestination.computeDestHash(encPub, sigPub, "meshsat", "node")
        val hash2 = RnsDestination.computeDestHash(encPub, sigPub, "meshsat", "message")
        assertFalse(hash1.contentEquals(hash2))
    }

    @Test
    fun `name hash is 10 bytes`() {
        val nHash = RnsDestination.nameHash("meshsat", "node")
        assertEquals(10, nHash.size)
    }

    @Test
    fun `name hash deterministic`() {
        val h1 = RnsDestination.nameHash("meshsat", "node")
        val h2 = RnsDestination.nameHash("meshsat", "node")
        assertArrayEquals(h1, h2)
    }

    @Test
    fun `identity hash uses encryption-first order`() {
        val encPub = ByteArray(32) { 0xAA.toByte() }
        val sigPub = ByteArray(32) { 0xBB.toByte() }
        val hash = RnsDestination.identityHash(encPub, sigPub)
        assertEquals(16, hash.size)

        // Verify ordering matters: swapped keys give different hash
        val swapped = RnsDestination.identityHash(sigPub, encPub)
        assertFalse(hash.contentEquals(swapped))
    }

    @Test
    fun `expand name with no aspects`() {
        assertEquals("meshsat", RnsDestination.expandName("meshsat"))
    }

    @Test
    fun `expand name with single aspect`() {
        assertEquals("meshsat.node", RnsDestination.expandName("meshsat", "node"))
    }

    @Test
    fun `expand name with multiple aspects`() {
        assertEquals("meshsat.node.status", RnsDestination.expandName("meshsat", "node", "status"))
    }

    @Test
    fun `plain destination hash has no identity`() {
        val h1 = RnsDestination.computePlainDestHash("meshsat", "broadcast")
        assertEquals(16, h1.size)

        val h2 = RnsDestination.computePlainDestHash("meshsat", "broadcast")
        assertArrayEquals(h1, h2)
    }

    @Test
    fun `truncated hash is 16 bytes`() {
        val hash = RnsDestination.truncatedHash(byteArrayOf(1, 2, 3))
        assertEquals(16, hash.size)
    }

    @Test
    fun `full hash is 32 bytes`() {
        val hash = RnsDestination.fullHash(byteArrayOf(1, 2, 3))
        assertEquals(32, hash.size)
    }

    @Test
    fun `random hash is 16 bytes`() {
        val hash = RnsDestination.randomHash()
        assertEquals(16, hash.size)
    }

    @Test
    fun `ratchet ID is 10 bytes`() {
        val rId = RnsDestination.ratchetId(ByteArray(32) { it.toByte() })
        assertEquals(10, rId.size)
    }

    // --- Constants validation ---

    @Test
    fun `MTU is 500`() {
        assertEquals(500, RnsConstants.MTU)
    }

    @Test
    fun `MDU is 464`() {
        assertEquals(464, RnsConstants.MDU)
    }

    @Test
    fun `ENCRYPTED_MDU is 383`() {
        assertEquals(383, RnsConstants.ENCRYPTED_MDU)
    }

    @Test
    fun `dest hash length is 16`() {
        assertEquals(16, RnsConstants.DEST_HASH_LEN)
    }

    @Test
    fun `HEADER_MINSIZE is 19`() {
        // 2 (flags+hops) + 1 (context) + 16 (dest) = 19
        // Note: HEADER_MINSIZE includes context slot in the count
        assertEquals(19, RnsConstants.HEADER_MINSIZE)
    }

    @Test
    fun `HEADER_MAXSIZE is 35`() {
        // 2 (flags+hops) + 1 (context) + 32 (transport_id+dest) = 35
        assertEquals(35, RnsConstants.HEADER_MAXSIZE)
    }
}
