package com.cubeos.meshsat

import com.cubeos.meshsat.reticulum.RnsConstants
import com.cubeos.meshsat.reticulum.RnsResource
import com.cubeos.meshsat.reticulum.RnsResourceAdv
import com.cubeos.meshsat.reticulum.RnsResourceChunk
import com.cubeos.meshsat.reticulum.RnsResourceComplete
import com.cubeos.meshsat.reticulum.RnsResourceProof
import com.cubeos.meshsat.reticulum.RnsTransferState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Reticulum resource transfer (MESHSAT-223).
 */
class RnsResourceTest {

    // --- Resource creation ---

    @Test
    fun `forSending creates correct chunk count`() {
        val payload = ByteArray(1000) { it.toByte() }
        val (resource, chunks) = RnsResource.forSending(payload, chunkSize = 200, linkId = ByteArray(16))

        assertEquals(5, resource.chunkCount)
        assertEquals(5, chunks.size)
        assertEquals(1000, resource.totalSize)
        assertEquals(200, resource.chunkSize)
        assertTrue(resource.isOutbound)
        assertEquals(RnsTransferState.ADVERTISING, resource.state)
    }

    @Test
    fun `forSending handles non-even chunk division`() {
        val payload = ByteArray(350) { it.toByte() }
        val (resource, chunks) = RnsResource.forSending(payload, chunkSize = 200, linkId = ByteArray(16))

        assertEquals(2, resource.chunkCount)
        assertEquals(200, chunks[0].size)
        assertEquals(150, chunks[1].size)
    }

    @Test
    fun `forSending single chunk for small payload`() {
        val payload = "hello".toByteArray()
        val (resource, chunks) = RnsResource.forSending(payload, chunkSize = 200, linkId = ByteArray(16))

        assertEquals(1, resource.chunkCount)
        assertEquals(1, chunks.size)
        assertArrayEquals(payload, chunks[0])
    }

    @Test
    fun `resource id is 16 bytes`() {
        val (resource, _) = RnsResource.forSending(ByteArray(100), 50, ByteArray(16))
        assertEquals(16, resource.id.size)
    }

    @Test
    fun `resource hash is 32 bytes`() {
        val (resource, _) = RnsResource.forSending(ByteArray(100), 50, ByteArray(16))
        assertEquals(32, resource.payloadHash.size)
    }

    // --- Progress tracking ---

    @Test
    fun `progress starts at zero`() {
        val (resource, _) = RnsResource.forSending(ByteArray(400), 100, ByteArray(16))
        assertEquals(0f, resource.progress, 0.01f)
    }

    @Test
    fun `progress tracks acked chunks for outbound`() {
        val (resource, _) = RnsResource.forSending(ByteArray(400), 100, ByteArray(16))
        resource.ackChunk(0)
        resource.ackChunk(1)
        assertEquals(0.5f, resource.progress, 0.01f)
    }

    @Test
    fun `progress tracks received chunks for inbound`() {
        val resource = RnsResource.forReceiving(
            ByteArray(16), 400, 100, 4, ByteArray(32), ByteArray(16)
        )
        resource.addChunk(0, ByteArray(100))
        resource.addChunk(1, ByteArray(100))
        assertEquals(0.5f, resource.progress, 0.01f)
    }

    @Test
    fun `isComplete when all chunks acked`() {
        val (resource, _) = RnsResource.forSending(ByteArray(200), 100, ByteArray(16))
        assertFalse(resource.isComplete)
        resource.ackChunk(0)
        resource.ackChunk(1)
        assertTrue(resource.isComplete)
    }

    @Test
    fun `pendingChunks returns un-acked indices`() {
        val (resource, _) = RnsResource.forSending(ByteArray(400), 100, ByteArray(16))
        resource.ackChunk(0)
        resource.ackChunk(2)
        val pending = resource.pendingChunks()
        assertEquals(listOf(1, 3), pending)
    }

    // --- Reassembly ---

    @Test
    fun `reassemble reconstructs original payload`() {
        val payload = ByteArray(350) { (it % 256).toByte() }
        val (_, chunks) = RnsResource.forSending(payload, chunkSize = 200, linkId = ByteArray(16))

        val receiver = RnsResource.forReceiving(
            ByteArray(16), 350, 200, 2, ByteArray(32), ByteArray(16)
        )
        for ((i, chunk) in chunks.withIndex()) {
            receiver.addChunk(i, chunk)
        }

        val reassembled = receiver.reassemble()
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled)
    }

    @Test
    fun `reassemble returns null when chunks missing`() {
        val receiver = RnsResource.forReceiving(
            ByteArray(16), 400, 100, 4, ByteArray(32), ByteArray(16)
        )
        receiver.addChunk(0, ByteArray(100))
        receiver.addChunk(2, ByteArray(100))
        assertNull(receiver.reassemble())
    }

    @Test
    fun `verifyHash succeeds for matching payload`() {
        val payload = "test payload data".toByteArray()
        val (resource, _) = RnsResource.forSending(payload, 100, ByteArray(16))
        assertTrue(resource.verifyHash(payload))
    }

    @Test
    fun `verifyHash fails for mismatched payload`() {
        val payload = "test payload data".toByteArray()
        val (resource, _) = RnsResource.forSending(payload, 100, ByteArray(16))
        assertFalse(resource.verifyHash("wrong data".toByteArray()))
    }

    // --- Wire format: Resource Advertisement ---

    @Test
    fun `resource adv marshal size`() {
        assertEquals(56, RnsResourceAdv.SIZE)
    }

    @Test
    fun `resource adv round-trip`() {
        val (resource, _) = RnsResource.forSending(ByteArray(1000), 200, ByteArray(16))
        val data = RnsResourceAdv.marshal(resource)
        assertEquals(56, data.size)

        val parsed = RnsResourceAdv.unmarshal(data)
        assertNotNull(parsed)
        assertArrayEquals(resource.id, parsed!!.resourceId)
        assertArrayEquals(resource.payloadHash, parsed.payloadHash)
        assertEquals(1000, parsed.totalSize)
        assertEquals(200, parsed.chunkSize)
        assertEquals(5, parsed.chunkCount)
    }

    @Test
    fun `resource adv rejects too-short data`() {
        assertNull(RnsResourceAdv.unmarshal(ByteArray(10)))
    }

    // --- Wire format: Resource Chunk ---

    @Test
    fun `resource chunk header size is 18`() {
        assertEquals(18, RnsResourceChunk.HEADER_SIZE)
    }

    @Test
    fun `resource chunk round-trip`() {
        val id = ByteArray(16) { 0xAA.toByte() }
        val chunkData = "chunk data here".toByteArray()
        val data = RnsResourceChunk.marshal(id, 7, chunkData)

        val parsed = RnsResourceChunk.unmarshal(data)
        assertNotNull(parsed)
        assertArrayEquals(id, parsed!!.resourceId)
        assertEquals(7, parsed.chunkIndex)
        assertArrayEquals(chunkData, parsed.chunkData)
    }

    // --- Wire format: Resource Proof ---

    @Test
    fun `resource proof size is 18`() {
        assertEquals(18, RnsResourceProof.SIZE)
    }

    @Test
    fun `resource proof round-trip`() {
        val id = ByteArray(16) { 0xBB.toByte() }
        val data = RnsResourceProof.marshal(id, 42)

        val parsed = RnsResourceProof.unmarshal(data)
        assertNotNull(parsed)
        assertArrayEquals(id, parsed!!.resourceId)
        assertEquals(42, parsed.chunkIndex)
    }

    // --- Wire format: Resource Complete ---

    @Test
    fun `resource complete size is 17`() {
        assertEquals(17, RnsResourceComplete.SIZE)
    }

    @Test
    fun `resource complete round-trip success`() {
        val id = ByteArray(16) { 0xCC.toByte() }
        val data = RnsResourceComplete.marshal(id, success = true)

        val parsed = RnsResourceComplete.unmarshal(data)
        assertNotNull(parsed)
        assertArrayEquals(id, parsed!!.resourceId)
        assertTrue(parsed.success)
    }

    @Test
    fun `resource complete round-trip failure`() {
        val id = ByteArray(16) { 0xDD.toByte() }
        val data = RnsResourceComplete.marshal(id, success = false)

        val parsed = RnsResourceComplete.unmarshal(data)
        assertNotNull(parsed)
        assertFalse(parsed!!.success)
    }

    // --- End-to-end transfer simulation ---

    @Test
    fun `full transfer simulation`() {
        val payload = ByteArray(500) { (it % 256).toByte() }
        val linkId = ByteArray(16) { 0x01 }
        val chunkSize = 200

        // Sender creates resource
        val (senderRes, chunks) = RnsResource.forSending(payload, chunkSize, linkId)
        assertEquals(3, chunks.size)

        // Sender marshals advertisement
        val advData = RnsResourceAdv.marshal(senderRes)
        val adv = RnsResourceAdv.unmarshal(advData)!!

        // Receiver creates resource from advertisement
        val receiverRes = RnsResource.forReceiving(
            adv.resourceId, adv.totalSize, adv.chunkSize, adv.chunkCount, adv.payloadHash, linkId
        )

        // Simulate chunk transfer with ACK
        for ((i, chunk) in chunks.withIndex()) {
            // Sender marshals chunk
            val chunkWire = RnsResourceChunk.marshal(senderRes.id, i, chunk)
            val parsedChunk = RnsResourceChunk.unmarshal(chunkWire)!!

            // Receiver stores chunk
            receiverRes.addChunk(parsedChunk.chunkIndex, parsedChunk.chunkData)

            // Receiver sends ACK
            val ackWire = RnsResourceProof.marshal(senderRes.id, i)
            val parsedAck = RnsResourceProof.unmarshal(ackWire)!!
            senderRes.ackChunk(parsedAck.chunkIndex)
        }

        // Both sides report complete
        assertTrue(senderRes.isComplete)
        assertTrue(receiverRes.isComplete)
        assertEquals(1f, senderRes.progress, 0.01f)

        // Receiver reassembles and verifies
        val reassembled = receiverRes.reassemble()!!
        assertArrayEquals(payload, reassembled)
        assertTrue(receiverRes.verifyHash(reassembled))

        // Complete notification
        val completeWire = RnsResourceComplete.marshal(senderRes.id, success = true)
        val parsedComplete = RnsResourceComplete.unmarshal(completeWire)!!
        assertTrue(parsedComplete.success)
    }
}
