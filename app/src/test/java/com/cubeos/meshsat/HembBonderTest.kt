package com.cubeos.meshsat

import com.cubeos.meshsat.hemb.HembBearerProfile
import com.cubeos.meshsat.hemb.HembBonder
import com.cubeos.meshsat.hemb.HembCodedSymbol
import com.cubeos.meshsat.hemb.HembFrame
import com.cubeos.meshsat.hemb.HembReassemblyBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class HembBonderTest {

    @Test
    fun `N=1 single bearer passthrough`() = runBlocking {
        val captured = CopyOnWriteArrayList<ByteArray>()
        val bearer = HembBearerProfile(
            index = 0,
            interfaceId = "mesh_0",
            channelType = "mesh",
            mtu = 237,
            sendFn = { captured.add(it) },
        )
        val bonder = HembBonder(listOf(bearer))
        val payload = "hello hemb".toByteArray()
        bonder.send(payload)

        assertEquals(1, captured.size)
        assertArrayEquals(payload, captured[0])
    }

    @Test
    fun `N=2 multi-bearer RLNC roundtrip`() = runBlocking {
        val frames = CopyOnWriteArrayList<ByteArray>()
        val bearers = listOf(
            HembBearerProfile(0, "mesh_0", "mesh", 237, sendFn = { frames.add(it) }),
            HembBearerProfile(1, "tcp_0", "tcp", 1400, sendFn = { frames.add(it) }),
        )
        val bonder = HembBonder(bearers)
        // Payload must be large enough to require multiple segments across 2 bearers.
        val payload = ByteArray(500) { (it % 256).toByte() }
        bonder.send(payload)

        assertTrue("should produce frames", frames.isNotEmpty())

        // All frames should be valid HeMB
        for (frame in frames) {
            assertTrue("frame should be HeMB", HembFrame.isHembFrame(frame))
        }

        // Feed all frames into reassembly buffer — should decode
        var decoded: ByteArray? = null
        val reassembly = HembReassemblyBuffer(deliverFn = { data -> decoded = data })
        for (frame in frames) {
            val result = reassembly.addFrame(frame)
            if (result != null) decoded = result
        }

        assertNotNull("should decode from N frames", decoded)
        // Decoded payload may have zero-padding from segmentation
        assertTrue("decoded should start with original",
            decoded!!.copyOfRange(0, payload.size).contentEquals(payload))
    }

    @Test
    fun `free bearers first allocation`() = runBlocking {
        val freeFrames = CopyOnWriteArrayList<ByteArray>()
        val paidFrames = CopyOnWriteArrayList<ByteArray>()
        val bearers = listOf(
            HembBearerProfile(0, "mesh_0", "mesh", 237, costPerMsg = 0.0, sendFn = { freeFrames.add(it) }),
            HembBearerProfile(1, "sbd_0", "iridium", 340, costPerMsg = 0.05, sendFn = { paidFrames.add(it) }),
        )
        val bonder = HembBonder(bearers)
        bonder.send("test".toByteArray())

        // Free bearer should get frames before paid
        assertTrue("free bearer should receive frames", freeFrames.isNotEmpty())
    }

    @Test
    fun `generation cleanup after decode`() {
        var delivered = 0
        val buf = HembReassemblyBuffer(deliverFn = { delivered++ })
        // K=1: single identity symbol decodes immediately
        buf.addSymbol(5, 0, HembCodedSymbol(0, 0, 1, byteArrayOf(1), byteArrayOf(0xAA.toByte())))
        assertEquals(1, delivered)
        assertEquals(0, buf.activeStreamCount) // cleaned up after decode
        // Reuse same stream+gen ID — must NOT hit "already decoded"
        buf.addSymbol(5, 0, HembCodedSymbol(0, 0, 1, byteArrayOf(1), byteArrayOf(0xBB.toByte())))
        assertEquals(2, delivered)
    }

    @Test
    fun `reassembly buffer reap`() {
        val buf = HembReassemblyBuffer()
        val sym = HembCodedSymbol(0, 0, 2, byteArrayOf(1, 0), byteArrayOf(0x01, 0x02))
        buf.addSymbol(0, 0, sym)
        assertTrue("should have active streams", buf.activeStreamCount > 0)

        // Sleep 1ms to ensure createdAt is in the past, then reap with 0 age
        Thread.sleep(1)
        val removed = buf.reap(0)
        assertTrue("should reap at least 1 stream", removed >= 1)
    }
}
