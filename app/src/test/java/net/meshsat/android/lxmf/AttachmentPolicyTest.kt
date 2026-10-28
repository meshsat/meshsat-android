package net.meshsat.android.lxmf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attachments travel on Reticulum links only; every other bearer carries the text (MESHSAT-1735). */
class AttachmentPolicyTest {
    @Test
    fun onlyReticulumCarriesThem() {
        assertTrue(AttachmentPolicy.carries("lxmf"))
        for (t in listOf("sms", "mesh", "iridium", "hub", "tak")) assertFalse(t, AttachmentPolicy.carries(t))
    }

    @Test
    fun sendButtonRules() {
        assertTrue(AttachmentPolicy.canSend("lxmf", "", true))
        assertFalse("a note alone cannot go by SMS", AttachmentPolicy.canSend("sms", "", true))
        assertTrue("the transcript can", AttachmentPolicy.canSend("sms", "meet at the bridge", true))
        assertFalse(AttachmentPolicy.canSend("lxmf", "  ", false))
        assertTrue(AttachmentPolicy.fits(60_000, 5_536))
        assertFalse(AttachmentPolicy.fits(60_000, 5_537))
    }

    @Test
    fun theLineUnderTheBox() {
        assertNull(AttachmentPolicy.note("lxmf", 0, 0, 0))
        assertEquals(
            "With a voice note 12 s, 18 KB, by Reticulum; the far end sees it once the whole message is across.",
            AttachmentPolicy.note("lxmf", 18 * 1024, 12_000, 0),
        )
        assertEquals(
            "By satellite only the text goes: a voice note 0.8 s, 900 B and photo 3 KB travels by Reticulum alone, so it is not sent.",
            AttachmentPolicy.note("iridium", 900, 800, 3 * 1024),
        )
        assertEquals("By SMS only the text goes: a photo 2 KB travels by Reticulum alone, so it is not sent.", AttachmentPolicy.note("sms", 0, 0, 2048))
        assertEquals("the mesh", AttachmentPolicy.bearerName("mesh"))
    }

    @Test
    fun units() {
        assertEquals("30 s", AttachmentPolicy.seconds(30_000))
        assertEquals("2.5 s", AttachmentPolicy.seconds(2_500))
        assertEquals("64 KB", AttachmentPolicy.size(LxAttachments.MAX_BYTES))
        assertEquals("1023 B", AttachmentPolicy.size(1023))
        assertEquals(30_000L, AttachmentPolicy.MAX_NOTE_MS)
    }
}
