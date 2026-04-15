package com.cubeos.meshsat

import com.cubeos.meshsat.hemb.*
import org.junit.Assert.*
import org.junit.Test

class HembCodecTest {

    // ── GF(256) arithmetic ──────────────────────────────────────────────

    @Test
    fun `HembGf256 add is XOR`() {
        assertEquals(0, HembGf256.add(42, 42))
        assertEquals(42 xor 17, HembGf256.add(42, 17))
    }

    @Test
    fun `HembGf256 multiply by 1 is identity`() {
        for (a in 0..255) {
            assertEquals(a, HembGf256.mul(a, 1))
        }
    }

    @Test
    fun `HembGf256 multiply by 0 is 0`() {
        for (a in 0..255) {
            assertEquals(0, HembGf256.mul(a, 0))
        }
    }

    @Test
    fun `HembGf256 multiply inverse equals 1`() {
        for (a in 1..255) {
            assertEquals("$a * inv($a) should be 1", 1, HembGf256.mul(a, HembGf256.inv(a)))
        }
    }

    @Test
    fun `HembGf256 commutativity`() {
        for (a in 1..50) {
            for (b in 1..50) {
                assertEquals(HembGf256.mul(a, b), HembGf256.mul(b, a))
            }
        }
    }

    @Test
    fun `HembGf256 associativity`() {
        val a = 42; val b = 17; val c = 99
        assertEquals(
            HembGf256.mul(HembGf256.mul(a, b), c),
            HembGf256.mul(a, HembGf256.mul(b, c))
        )
    }

    @Test
    fun `HembGf256 uses polynomial 0x11B not 0x11D`() {
        // Verify generator 0x03 under polynomial 0x11B:
        // alpha^0 = 1, alpha^1 = 3, alpha^2 = 5, alpha^3 = 15, alpha^4 = 17
        assertEquals(1, HembGf256.EXP[0])
        assertEquals(3, HembGf256.EXP[1])
        assertEquals(5, HembGf256.EXP[2])
        assertEquals(15, HembGf256.EXP[3])
        assertEquals(17, HembGf256.EXP[4])
    }

    @Test
    fun `HembGf256 exp table wraps correctly`() {
        // EXP[i] == EXP[i+255] for all i in 0..254
        for (i in 0 until 255) {
            assertEquals("EXP[$i] != EXP[${i + 255}]", HembGf256.EXP[i], HembGf256.EXP[i + 255])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `HembGf256 inv zero throws`() {
        HembGf256.inv(0)
    }

    // ── Gaussian elimination ────────────────────────────────────────────

    @Test
    fun `Gaussian elimination identity matrix`() {
        val coeffs = HembGfMatrix(3, 3)
        coeffs[0, 0] = 1; coeffs[1, 1] = 1; coeffs[2, 2] = 1
        val payloads = listOf(
            byteArrayOf(10, 20),
            byteArrayOf(30, 40),
            byteArrayOf(50, 60),
        )
        val result = hembGaussianEliminate(coeffs, payloads)
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(10, 20), result!![0])
        assertArrayEquals(byteArrayOf(30, 40), result[1])
        assertArrayEquals(byteArrayOf(50, 60), result[2])
    }

    @Test
    fun `Gaussian elimination rank deficient returns null`() {
        val coeffs = HembGfMatrix(2, 3)
        coeffs[0, 0] = 1; coeffs[0, 1] = 2; coeffs[0, 2] = 3
        coeffs[1, 0] = 1; coeffs[1, 1] = 2; coeffs[1, 2] = 3 // duplicate row
        val payloads = listOf(byteArrayOf(1), byteArrayOf(1))
        assertNull(hembGaussianEliminate(coeffs, payloads))
    }

    @Test
    fun `ComputeRank identity matrix`() {
        val rows = listOf(
            byteArrayOf(1, 0, 0),
            byteArrayOf(0, 1, 0),
            byteArrayOf(0, 0, 1),
        )
        assertEquals(3, hembComputeRank(rows, 3))
    }

    @Test
    fun `ComputeRank with dependent row`() {
        val rows = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5, 6),
        )
        assertEquals(2, hembComputeRank(rows, 3))
    }

    // ── RLNC encode/decode ──────────────────────────────────────────────

    @Test
    fun `RLNC encode and decode with exact K symbols`() {
        val original = "Hello HeMB World!".toByteArray()
        val symSize = 5
        val segments = HembRlncEncoder.segmentPayload(original, symSize)
        val k = segments.size

        val symbols = HembRlncEncoder.encode(0, segments, k)
        assertEquals(k, symbols.size)

        val decoder = HembRlncDecoder(k, symSize)
        for (sym in symbols) {
            decoder.feed(sym)
        }
        assertTrue(decoder.isSolvable)

        val recovered = decoder.solve()!!
        assertEquals(k, recovered.size)

        val result = ByteArray(original.size)
        var offset = 0
        for (seg in recovered) {
            val len = minOf(seg.size, original.size - offset)
            System.arraycopy(seg, 0, result, offset, len)
            offset += seg.size
        }
        assertArrayEquals(original, result)
    }

    @Test
    fun `RLNC decode with N greater than K`() {
        val data = ByteArray(50) { (it * 7 + 3).toByte() }
        val symSize = 10
        val segments = HembRlncEncoder.segmentPayload(data, symSize)
        val k = segments.size
        val n = k + 3

        val symbols = HembRlncEncoder.encode(42, segments, n)
        assertEquals(n, symbols.size)

        // Feed only first K symbols.
        val decoder = HembRlncDecoder(k, symSize)
        for (sym in symbols.take(k)) {
            decoder.feed(sym)
        }
        assertTrue(decoder.isSolvable)

        val recovered = decoder.solve()!!
        val result = ByteArray(data.size)
        var offset = 0
        for (seg in recovered) {
            val len = minOf(seg.size, data.size - offset)
            System.arraycopy(seg, 0, result, offset, len)
            offset += seg.size
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `RLNC progressive rank tracking`() {
        val segments = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5, 6))
        val symbols = HembRlncEncoder.encode(0, segments, 4)

        val decoder = HembRlncDecoder(3, 2)
        assertEquals(0, decoder.rank)

        decoder.feed(symbols[0])
        assertEquals(1, decoder.rank)

        decoder.feed(symbols[1])
        assertEquals(2, decoder.rank)

        decoder.feed(symbols[2])
        assertEquals(3, decoder.rank)
        assertTrue(decoder.isSolvable)
    }

    @Test
    fun `RLNC duplicate symbol does not increase rank`() {
        val segments = listOf(byteArrayOf(10, 20), byteArrayOf(30, 40))
        val symbols = HembRlncEncoder.encode(0, segments, 3)

        val decoder = HembRlncDecoder(2, 2)
        assertTrue(decoder.feed(symbols[0]))
        assertEquals(1, decoder.rank)

        assertFalse(decoder.feed(symbols[0]))
        assertEquals(1, decoder.rank)
    }

    @Test
    fun `hembTryDecode convenience function`() {
        val segments = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val symbols = HembRlncEncoder.encode(0, segments, 3)
        val result = hembTryDecode(symbols, 2)
        assertNotNull(result)
        assertEquals(2, result!!.size)
    }

    // ── CRC-8 ───────────────────────────────────────────────────────────

    @Test
    fun `CRC-8 empty data is zero`() {
        assertEquals(0.toByte(), HembFrame.crc8(byteArrayOf()))
    }

    @Test
    fun `CRC-8 deterministic`() {
        val data = byteArrayOf(0x48, 0x4D, 0x00, 0x01, 0x02, 0x03, 0x04)
        val crc1 = HembFrame.crc8(data)
        val crc2 = HembFrame.crc8(data)
        assertEquals(crc1, crc2)
    }

    // ── Frame format ────────────────────────────────────────────────────

    @Test
    fun `extended frame marshal and parse round-trip`() {
        val sym = HembCodedSymbol(
            genId = 42,
            symbolIndex = 7,
            k = 3,
            coefficients = byteArrayOf(0x11, 0x22, 0x33),
            data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
        )
        val frame = HembFrame.marshalExtended(
            streamId = 5,
            sym = sym,
            bearerIndex = 2,
            totalN = 5,
        )

        assertTrue(HembFrame.isHembFrame(frame))

        val parsed = HembFrame.parseSymbol(frame)
        assertNotNull(parsed)
        assertEquals(5, parsed!!.streamId)
        assertEquals(2, parsed.bearerIndex)
        assertEquals(42, parsed.symbol.genId)
        assertEquals(3, parsed.symbol.k)
        assertEquals(5, parsed.n)
        assertEquals(HembFrame.HEADER_MODE_EXTENDED, parsed.headerMode)
        assertArrayEquals(sym.coefficients, parsed.symbol.coefficients)
        assertArrayEquals(sym.data, parsed.symbol.data)
    }

    @Test
    fun `invalid CRC rejects frame`() {
        val sym = HembCodedSymbol(0, 0, 1, byteArrayOf(1), byteArrayOf(2))
        val frame = HembFrame.marshalExtended(0, sym, 0, 1)
        frame[15] = (frame[15].toInt() xor 0xFF).toByte() // corrupt CRC
        assertNull(HembFrame.parseSymbol(frame))
    }

    @Test
    fun `headerOverhead returns correct sizes`() {
        assertEquals(16, HembFrame.headerOverhead(HembFrame.HEADER_MODE_EXTENDED))
        assertEquals(8, HembFrame.headerOverhead(HembFrame.HEADER_MODE_COMPACT))
        assertEquals(0, HembFrame.headerOverhead(HembFrame.HEADER_MODE_IMPLICIT))
    }

    // ── Reassembly buffer ───────────────────────────────────────────────

    @Test
    fun `reassembly buffer decodes when K symbols received`() {
        val segments = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val symbols = HembRlncEncoder.encode(0, segments, 3)

        var delivered: ByteArray? = null
        val buf = HembReassemblyBuffer(deliverFn = { delivered = it })

        // Feed first symbol — not enough yet.
        assertNull(buf.addSymbol(0, 0, symbols[0]))
        assertNull(delivered)

        // Feed second symbol from different bearer — should decode.
        val result = buf.addSymbol(0, 1, symbols[1])
        assertNotNull(result)
        assertNotNull(delivered)
    }

    @Test
    fun `reassembly buffer rejects duplicate generation after decode`() {
        val segments = listOf(byteArrayOf(10))
        val symbols = HembRlncEncoder.encode(0, segments, 2)

        val buf = HembReassemblyBuffer()
        assertNotNull(buf.addSymbol(0, 0, symbols[0])) // K=1, decodes immediately
        assertNull(buf.addSymbol(0, 1, symbols[1])) // already decoded
    }

    @Test
    fun `reassembly buffer reap removes old streams`() {
        val buf = HembReassemblyBuffer()
        val sym = HembRlncEncoder.encode(0, listOf(byteArrayOf(1), byteArrayOf(2)), 3)[0]
        buf.addSymbol(0, 0, sym)
        assertEquals(1, buf.activeStreamCount)

        // Reap with 0ms age removes everything.
        val removed = buf.reap(maxAgeMs = 0)
        assertEquals(1, removed)
        assertEquals(0, buf.activeStreamCount)
    }

    // ── Profiles ────────────────────────────────────────────────────────

    @Test
    fun `selectRedundancy returns at least 1_0`() {
        val bearers = listOf(
            testBearer(channelType = "tcp", lossRate = 0.0, costPerMsg = 0.0),
        )
        val r = HembProfiles.selectRedundancy(bearers)
        assertTrue(r >= 1.0)
    }

    @Test
    fun `selectRedundancy P0 priority increases redundancy`() {
        val bearers = listOf(
            testBearer(channelType = "mesh", lossRate = 0.10, costPerMsg = 0.0),
        )
        val rBestEffort = HembProfiles.selectRedundancy(bearers, priority = 2)
        val rCritical = HembProfiles.selectRedundancy(bearers, priority = 0)
        assertTrue("P0 should get more redundancy", rCritical > rBestEffort)
    }

    @Test
    fun `selectRedundancy cost dampening for paid bearers`() {
        val paid = listOf(
            testBearer(channelType = "iridium_sbd", lossRate = 0.01, costPerMsg = 0.05),
        )
        val free = listOf(
            testBearer(channelType = "mesh", lossRate = 0.01, costPerMsg = 0.0),
        )
        val rPaid = HembProfiles.selectRedundancy(paid)
        val rFree = HembProfiles.selectRedundancy(free)
        assertTrue("Paid should have lower redundancy", rPaid <= rFree)
    }

    @Test
    fun `repairSymbols capped at 1 for paid bearers`() {
        val paid = testBearer(channelType = "iridium_sbd", lossRate = 0.50, costPerMsg = 0.05)
        val repair = HembProfiles.repairSymbols(paid, sourceCount = 10)
        assertEquals(1, repair)
    }

    @Test
    fun `repairSymbols zero for zero source`() {
        val b = testBearer(channelType = "mesh", lossRate = 0.30)
        assertEquals(0, HembProfiles.repairSymbols(b, 0))
    }

    // ── Bearer selector ─────────────────────────────────────────────────

    @Test
    fun `BearerSelector filters offline bearers`() {
        val selector = BearerSelector()
        selector.register(BearerSelector.bleDefaults().copy(
            onlineFn = { true },
            healthFn = { 80 },
            sendFn = {},
        ))
        selector.register(BearerSelector.smsDefaults().copy(
            onlineFn = { false },
            healthFn = { 90 },
            sendFn = {},
        ))
        val active = selector.activeBearers()
        assertEquals(1, active.size)
        assertEquals("ble_0", active[0].interfaceId)
    }

    @Test
    fun `BearerSelector filters zero-health bearers`() {
        val selector = BearerSelector()
        selector.register(BearerSelector.bleDefaults().copy(
            onlineFn = { true },
            healthFn = { 0 },
            sendFn = {},
        ))
        assertEquals(0, selector.activeBearers().size)
    }

    // ── Bond group QR parsing ───────────────────────────────────────────

    @Test
    fun `QR payload parse round-trip`() {
        // Build a v1 QR payload manually.
        val groupId = ByteArray(16) { it.toByte() }
        val label = "test-bond"
        val members = listOf("ble_0", "sms_0")

        val buf = java.io.ByteArrayOutputStream()
        buf.write(1) // version
        buf.write(groupId)
        buf.write(label.length)
        buf.write(label.toByteArray())
        buf.write(members.size)
        for (m in members) {
            buf.write(m.length)
            buf.write(m.toByteArray())
        }
        // cost budget as double (8 bytes BE)
        val costBuf = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.BIG_ENDIAN)
        costBuf.putDouble(1.50)
        buf.write(costBuf.array())

        val config = HembBondGroupManager.parseQrPayload(buf.toByteArray())
        assertNotNull(config)
        assertEquals("test-bond", config!!.label)
        assertEquals(2, config.members.size)
        assertEquals("ble_0", config.members[0])
        assertEquals("sms_0", config.members[1])
        assertEquals(1.50, config.costBudget, 0.001)
    }

    @Test
    fun `QR payload invalid version returns null`() {
        val data = byteArrayOf(99) // invalid version
        assertNull(HembBondGroupManager.parseQrPayload(data))
    }

    // ── End-to-end encode-frame-reassemble ──────────────────────────────

    @Test
    fun `end-to-end encode through frame to reassembly`() {
        val payload = "End-to-end HeMB test payload for bonded delivery!".toByteArray()
        val symSize = 10
        val segments = HembRlncEncoder.segmentPayload(payload, symSize)
        val k = segments.size
        val n = k + 2

        val symbols = HembRlncEncoder.encode(0, segments, n)

        // Marshal each symbol into a frame.
        val frames = symbols.mapIndexed { i, sym ->
            HembFrame.marshalExtended(
                streamId = 1,
                sym = sym,
                bearerIndex = i % 3,
                totalN = n,
            )
        }

        // Reassemble from frames.
        var decoded: ByteArray? = null
        val buf = HembReassemblyBuffer(deliverFn = { decoded = it })

        for (frame in frames) {
            assertTrue(HembFrame.isHembFrame(frame))
            val result = buf.addFrame(frame)
            if (result != null) {
                // Decoded — payload should match (may have trailing zero-pad)
                assertTrue(result.size >= payload.size)
                assertArrayEquals(payload, result.copyOfRange(0, payload.size))
                break
            }
        }
        assertNotNull("Should have decoded", decoded)
    }

    // ── HembBondStats ───────────────────────────────────────────────────

    @Test
    fun `HembBondStats defaults are zero`() {
        val stats = HembBondStats()
        assertEquals(0, stats.activeStreams)
        assertEquals(0L, stats.symbolsSent)
        assertEquals(0.0, stats.costIncurred, 0.0)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun testBearer(
        channelType: String = "mesh",
        lossRate: Double = 0.1,
        costPerMsg: Double = 0.0,
        mtu: Int = 237,
    ) = HembBearerProfile(
        index = 0,
        interfaceId = "${channelType}_0",
        channelType = channelType,
        mtu = mtu,
        costPerMsg = costPerMsg,
        lossRate = lossRate,
        healthScore = 100,
        sendFn = {},
    )
}
