package net.meshsat.android.lxmf

import net.meshsat.android.rns.MpArray
import net.meshsat.android.rns.MpBin
import net.meshsat.android.rns.MpInt
import net.meshsat.android.rns.MpMap
import net.meshsat.android.rns.MpStr
import net.meshsat.android.rns.Msgpack
import net.meshsat.android.rns.RnsIdentity
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/** The audio and image fields as the Bridge's fields.go writes and reads them (MESHSAT-1735). */
class LxAttachmentsTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun addProvider() { if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider()) }
    }

    private val audio = LxAttachment.audio(LxAttachments.AUDIO_OPUS_OGG, ByteArray(600) { (it * 7).toByte() })
    private val image = LxAttachment.image("webp", ByteArray(2048) { (it * 3).toByte() })

    @Test
    fun fieldsAreTheUpstreamShapes() {
        val f = LxAttachments.withAttachments(MpMap(emptyList()), listOf(audio, image))
        // Insertion order: the attachments in the order given, after what was there.
        assertEquals(listOf(LxMessage.FIELD_AUDIO, LxMessage.FIELD_IMAGE), f.entries.map { (it.first as MpInt).value })
        val a = f[LxMessage.FIELD_AUDIO] as MpArray
        assertEquals(MpInt(0x10), a.items[0]); assertEquals(MpBin(audio.data), a.items[1])
        val i = f[LxMessage.FIELD_IMAGE] as MpArray
        assertEquals(MpStr("webp"), i.items[0]); assertEquals(MpBin(image.data), i.items[1])
        // Byte for byte what Python's umsgpack writes for {7: [16, b"..."], 6: ["webp", b"..."]}.
        val packed = Msgpack.pack(f)
        assertEquals(0x82.toByte(), packed[0])                 // fixmap of 2
        assertEquals(0x07.toByte(), packed[1]); assertEquals(0x92.toByte(), packed[2]); assertEquals(0x10.toByte(), packed[3])
        assertEquals(0xC5.toByte(), packed[4])                 // bin16 of 600
        assertEquals(0x02.toByte(), packed[5]); assertEquals(0x58.toByte(), packed[6])
    }

    @Test
    fun readBackImageFirstAndOtherFieldsUntouched() {
        val mid = LxMid.fields(ByteArray(16) { it.toByte() })
        val f = LxAttachments.withAttachments(mid, listOf(audio, image))
        val back = LxAttachments.attachmentsOf(f)
        assertEquals(listOf("image", "audio"), back.map { it.kind })
        assertArrayEquals(image.data, back[0].data); assertEquals("webp", back[0].format)
        assertArrayEquals(audio.data, back[1].data); assertEquals(0x10, back[1].mode)
        assertArrayEquals(ByteArray(16) { it.toByte() }, LxMid.midOf(f))
        val stripped = LxAttachments.withoutAttachments(f)
        assertEquals(mid.entries.size, stripped.entries.size)
        assertNull(stripped[LxMessage.FIELD_AUDIO]); assertNull(stripped[LxMessage.FIELD_IMAGE])
    }

    @Test
    fun aFieldOfAnotherShapeIsIgnoredAndFormatsAreLowerCased() {
        val odd = MpMap(listOf(
            MpInt(LxMessage.FIELD_AUDIO) to MpBin(ByteArray(3)),                                 // not [mode, bytes]
            MpInt(LxMessage.FIELD_IMAGE) to MpArray(MpStr("WebP"), MpBin(ByteArray(5))),        // upper case name
        ))
        val back = LxAttachments.attachmentsOf(odd)
        assertEquals(1, back.size); assertEquals("webp", back[0].format)
        assertTrue(LxAttachments.attachmentsOf(MpMap(listOf(MpInt(LxMessage.FIELD_AUDIO) to MpArray(MpInt(3), MpBin(ByteArray(0)))))).isEmpty())
    }

    @Test
    fun capsAndValidation() {
        val big = LxAttachment.audio(0x03, ByteArray(LxAttachments.MAX_BYTES - 100))
        val photo = LxAttachment.image("webp", ByteArray(200))
        assertTrue(runCatching { LxAttachments.withAttachments(MpMap(emptyList()), listOf(big, photo)) }.exceptionOrNull() is LxmfException)
        assertTrue(runCatching { LxAttachments.withAttachments(MpMap(emptyList()), listOf(audio, audio)) }.exceptionOrNull() is LxmfException)
        assertTrue(runCatching { LxAttachments.withAttachments(MpMap(emptyList()), listOf(LxAttachment.audio(0x42, ByteArray(1)))) }.exceptionOrNull() is LxmfException)
        assertEquals("image format must be lower-case letters and digits", LxAttachment.image("We-bp", ByteArray(1)).validate())
        assertNull(LxAttachment.image("png", ByteArray(1)).validate())
        assertEquals("attachment has no bytes", LxAttachment.audio(0x10, ByteArray(0)).validate())
        // Exactly the cap fits.
        LxAttachments.withAttachments(MpMap(emptyList()), listOf(LxAttachment.audio(0x03, ByteArray(LxAttachments.MAX_BYTES))))
    }

    @Test
    fun namesTypesAndPreviews() {
        assertEquals("Codec2 700C", LxAttachments.audioModeName(0x03))
        assertEquals("Opus OGG", LxAttachments.audioModeName(0x10))
        assertEquals("custom", LxAttachments.audioModeName(0xFF))
        assertEquals("mode 0x42", LxAttachments.audioModeName(0x42))
        assertEquals("audio/codec2", LxAttachment.audio(0x03, ByteArray(1)).contentType)
        assertEquals("audio/ogg", audio.contentType)
        assertEquals("image/webp", image.contentType)
        assertEquals("image/jpeg", LxAttachment.image("jpg", ByteArray(1)).contentType)
        assertEquals("audio Opus OGG 600 B, image webp 2048 B", LxAttachments.describe(listOf(audio, image)))
        assertEquals("[audio Opus OGG 600 B]", LxAttachments.preview("", listOf(audio)))
        assertEquals("hello", LxAttachments.preview("hello", listOf(audio)))
        assertEquals("", LxAttachments.preview("", emptyList()))
    }

    /** The fields survive pack and unpack, and the hash covers them. */
    @Test
    fun packedMessageCarriesTheFields() {
        val a = RnsIdentity.generate(); val b = RnsIdentity.generate()
        val f = LxAttachments.withAttachments(LxMid.fields(LxMid.newMid()), listOf(audio, image))
        val m = LxMessage(b.destHash(LxMessage.DELIVERY_NAME), a.destHash(LxMessage.DELIVERY_NAME), content = "listen".toByteArray(), fields = f)
        m.pack(a)
        val back = LxMessage.unpack(m.packed) { a.sigPubRaw }
        assertTrue(back.signatureValid)
        assertEquals(m.hashHex, back.hashHex)
        val atts = LxAttachments.attachmentsOf(back.fields)
        assertEquals(2, atts.size)
        assertArrayEquals(image.data, atts[0].data); assertArrayEquals(audio.data, atts[1].data)
        assertTrue("a note never fits one packet", m.contentSize() > LxMessage.LINK_PACKET_MAX_CONTENT)
        // A text-only message of the same words does.
        val small = LxMessage(m.dest, m.source, content = "listen".toByteArray(), fields = LxMid.fields(LxMid.newMid()))
        small.pack(a)
        assertFalse(small.contentSize() > LxMessage.ENCRYPTED_PACKET_MAX_CONTENT)
    }
}
