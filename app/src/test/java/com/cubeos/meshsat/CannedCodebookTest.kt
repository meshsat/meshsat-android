package com.cubeos.meshsat

import com.cubeos.meshsat.codec.CannedCodebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CannedCodebook (Phase D: canned messages).
 * Tests encode/decode, reverse lookup, wire format.
 */
class CannedCodebookTest {

    @Test
    fun `default codebook has 30 entries`() {
        assertEquals(30, CannedCodebook.DEFAULT_ENTRIES.size)
    }

    @Test
    fun `encode produces 2-byte wire format`() {
        val encoded = CannedCodebook.encode(1)
        assertEquals(2, encoded.size)
        assertEquals(CannedCodebook.HEADER_CANNED.toByte(), encoded[0])
        assertEquals(1.toByte(), encoded[1])
    }

    @Test
    fun `decode default codebook by ID`() {
        val codebook = CannedCodebook.default
        val data = byteArrayOf(CannedCodebook.HEADER_CANNED.toByte(), 1)
        val text = codebook.decode(data)
        assertEquals("Copy.", text)
    }

    @Test
    fun `decode all 30 messages`() {
        val codebook = CannedCodebook.default
        for (id in 1..30) {
            val data = CannedCodebook.encode(id)
            val text = codebook.decode(data)
            assertNotNull("ID $id should decode", text)
            assertTrue("ID $id should not be blank", text.isNotBlank())
        }
    }

    @Test
    fun `reverse lookup finds message by text`() {
        val codebook = CannedCodebook.default
        val id = codebook.lookupByText("SOS — need immediate help.")
        assertNotNull(id)
        assertEquals(25, id)
    }

    @Test
    fun `reverse lookup returns null for unknown text`() {
        val codebook = CannedCodebook.default
        val id = codebook.lookupByText("this is not a canned message")
        assertNull(id)
    }

    @Test
    fun `isCanned detects header byte`() {
        val data = byteArrayOf(CannedCodebook.HEADER_CANNED.toByte(), 5)
        assertTrue(CannedCodebook.isCanned(data))
    }

    @Test
    fun `isCanned rejects non-canned data`() {
        val data = byteArrayOf(0x50, 0x01)
        assertTrue(!CannedCodebook.isCanned(data))
    }

    @Test
    fun `known messages match expected text`() {
        val entries = CannedCodebook.DEFAULT_ENTRIES
        assertEquals("Copy.", entries[1])
        assertEquals("Roger.", entries[2])
        assertEquals("Negative.", entries[3])
        assertEquals("SOS — need immediate help.", entries[25])
    }
}
