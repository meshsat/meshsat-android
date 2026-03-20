package com.cubeos.meshsat

import com.cubeos.meshsat.reticulum.RnsConstants
import com.cubeos.meshsat.reticulum.RnsDestination
import com.cubeos.meshsat.reticulum.RnsEncryptionMode
import com.cubeos.meshsat.reticulum.RnsHkdf
import com.cubeos.meshsat.reticulum.RnsLink
import com.cubeos.meshsat.reticulum.RnsLinkManager
import com.cubeos.meshsat.reticulum.RnsLinkProof
import com.cubeos.meshsat.reticulum.RnsLinkRequest
import com.cubeos.meshsat.reticulum.RnsLinkState
import com.cubeos.meshsat.reticulum.RnsPacket
import com.cubeos.meshsat.reticulum.RnsSignalling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Reticulum link handshake (MESHSAT-211).
 */
class RnsLinkTest {

    // --- HKDF ---

    @Test
    fun `HKDF extract produces 32 bytes`() {
        val prk = RnsHkdf.extract(ByteArray(16), ByteArray(32))
        assertEquals(32, prk.size)
    }

    @Test
    fun `HKDF expand produces requested length`() {
        val prk = RnsHkdf.extract(null, ByteArray(32) { it.toByte() })
        val okm = RnsHkdf.expand(prk, null, 64)
        assertEquals(64, okm.size)
    }

    @Test
    fun `HKDF derive is deterministic`() {
        val secret = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (0xFF - it).toByte() }
        val k1 = RnsHkdf.derive(64, secret, salt)
        val k2 = RnsHkdf.derive(64, secret, salt)
        assertArrayEquals(k1, k2)
    }

    @Test
    fun `HKDF different salts produce different keys`() {
        val secret = ByteArray(32) { it.toByte() }
        val k1 = RnsHkdf.derive(32, secret, ByteArray(16) { 0x01 })
        val k2 = RnsHkdf.derive(32, secret, ByteArray(16) { 0x02 })
        assertFalse(k1.contentEquals(k2))
    }

    @Test
    fun `HKDF deriveLinkKeys produces 32-byte send and recv keys`() {
        val (send, recv) = RnsHkdf.deriveLinkKeys(
            ByteArray(32) { it.toByte() },
            ByteArray(16),
            isInitiator = true,
        )
        assertEquals(32, send.size)
        assertEquals(32, recv.size)
    }

    @Test
    fun `HKDF initiator and responder keys are swapped`() {
        val secret = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { 0xAA.toByte() }
        val (iSend, iRecv) = RnsHkdf.deriveLinkKeys(secret, salt, isInitiator = true)
        val (rSend, rRecv) = RnsHkdf.deriveLinkKeys(secret, salt, isInitiator = false)
        // Initiator's send key = responder's recv key, and vice versa
        assertArrayEquals(iSend, rRecv)
        assertArrayEquals(iRecv, rSend)
    }

    @Test
    fun `HKDF null salt uses zero bytes`() {
        val secret = ByteArray(32) { it.toByte() }
        val k1 = RnsHkdf.derive(32, secret, null)
        val k2 = RnsHkdf.derive(32, secret, ByteArray(32))
        assertArrayEquals(k1, k2)
    }

    @Test
    fun `HKDF produces different output for different info`() {
        val secret = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16)
        val k1 = RnsHkdf.derive(32, secret, salt, "info1".toByteArray())
        val k2 = RnsHkdf.derive(32, secret, salt, "info2".toByteArray())
        assertFalse(k1.contentEquals(k2))
    }

    // --- Signalling ---

    @Test
    fun `signalling round-trip`() {
        val sig = RnsSignalling(mtu = 340, encryptionMode = RnsEncryptionMode.AES_256_GCM)
        val data = sig.marshal()
        assertEquals(3, data.size)
        val parsed = RnsSignalling.unmarshal(data)
        assertEquals(340, parsed.mtu)
        assertEquals(RnsEncryptionMode.AES_256_GCM, parsed.encryptionMode)
    }

    @Test
    fun `signalling default values`() {
        val sig = RnsSignalling()
        assertEquals(RnsConstants.MTU, sig.mtu)
        assertEquals(RnsEncryptionMode.AES_256_GCM, sig.encryptionMode)
    }

    @Test
    fun `signalling CBC mode`() {
        val sig = RnsSignalling(encryptionMode = RnsEncryptionMode.AES_256_CBC)
        val data = sig.marshal()
        val parsed = RnsSignalling.unmarshal(data)
        assertEquals(RnsEncryptionMode.AES_256_CBC, parsed.encryptionMode)
    }

    // --- Link Request Data ---

    @Test
    fun `link request data round-trip`() {
        val ephPub = ByteArray(32) { it.toByte() }
        val sigPub = ByteArray(32) { (32 + it).toByte() }
        val req = RnsLinkRequest(ephPub, sigPub, RnsSignalling())
        val data = req.marshal()
        assertEquals(67, data.size)  // 32 + 32 + 3

        val parsed = RnsLinkRequest.unmarshal(data)
        assertArrayEquals(ephPub, parsed.ephemeralPub)
        assertArrayEquals(sigPub, parsed.signingPub)
        assertEquals(RnsConstants.MTU, parsed.signalling.mtu)
    }

    @Test
    fun `link request framed in RnsPacket`() {
        val destHash = ByteArray(16) { 0xAA.toByte() }
        val reqData = RnsLinkRequest(
            ByteArray(32), ByteArray(32), RnsSignalling()
        )
        val packet = RnsPacket.linkRequest(destHash, reqData.marshal())
        val wire = packet.marshal()

        val parsed = RnsPacket.unmarshal(wire)
        assertEquals(RnsConstants.PACKET_LINKREQUEST, parsed.packetType)
        assertArrayEquals(destHash, parsed.destHash)

        val parsedReq = RnsLinkRequest.unmarshal(parsed.data)
        assertEquals(RnsConstants.MTU, parsedReq.signalling.mtu)
    }

    // --- Link Proof Data ---

    @Test
    fun `link proof data round-trip`() {
        val sig = ByteArray(64) { it.toByte() }
        val ephPub = ByteArray(32) { (64 + it).toByte() }
        val proof = RnsLinkProof(sig, ephPub, RnsSignalling())
        val data = proof.marshal()
        assertEquals(99, data.size)  // 64 + 32 + 3

        val parsed = RnsLinkProof.unmarshal(data)
        assertArrayEquals(sig, parsed.signature)
        assertArrayEquals(ephPub, parsed.ephemeralPub)
    }

    @Test
    fun `link proof framed as PROOF packet`() {
        val linkId = ByteArray(16) { 0xBB.toByte() }
        val proofData = RnsLinkProof(
            ByteArray(64), ByteArray(32), RnsSignalling()
        )
        val packet = RnsPacket.proof(linkId, proofData.marshal())
        val wire = packet.marshal()

        val parsed = RnsPacket.unmarshal(wire)
        assertEquals(RnsConstants.PACKET_PROOF, parsed.packetType)
        assertArrayEquals(linkId, parsed.destHash)
    }

    // --- Link ID ---

    @Test
    fun `link ID is 16 bytes`() {
        val reqData = ByteArray(67)
        val linkId = RnsLinkManager.computeLinkId(reqData)
        assertEquals(16, linkId.size)
    }

    @Test
    fun `link ID is deterministic`() {
        val reqData = ByteArray(67) { it.toByte() }
        val id1 = RnsLinkManager.computeLinkId(reqData)
        val id2 = RnsLinkManager.computeLinkId(reqData)
        assertArrayEquals(id1, id2)
    }

    @Test
    fun `different requests produce different link IDs`() {
        val id1 = RnsLinkManager.computeLinkId(ByteArray(67) { 0x01 })
        val id2 = RnsLinkManager.computeLinkId(ByteArray(67) { 0x02 })
        assertFalse(id1.contentEquals(id2))
    }

    // --- RnsLink Encryption ---

    @Test
    fun `AES-256-GCM encrypt-decrypt round-trip`() {
        val key1 = ByteArray(32) { it.toByte() }
        val key2 = ByteArray(32) { (32 + it).toByte() }

        val sender = RnsLink(
            id = ByteArray(16), destHash = ByteArray(16),
            state = RnsLinkState.ACTIVE,
            encryptionMode = RnsEncryptionMode.AES_256_GCM,
            sendKey = key1, recvKey = key2, isInitiator = true,
        )
        val receiver = RnsLink(
            id = ByteArray(16), destHash = ByteArray(16),
            state = RnsLinkState.ACTIVE,
            encryptionMode = RnsEncryptionMode.AES_256_GCM,
            sendKey = key2, recvKey = key1, isInitiator = false,
        )

        val plaintext = "hello reticulum link".toByteArray()
        val encrypted = sender.encrypt(plaintext)
        val decrypted = receiver.decrypt(encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `AES-256-CBC encrypt-decrypt round-trip`() {
        val key1 = ByteArray(32) { it.toByte() }
        val key2 = ByteArray(32) { (32 + it).toByte() }

        val sender = RnsLink(
            id = ByteArray(16), destHash = ByteArray(16),
            state = RnsLinkState.ACTIVE,
            encryptionMode = RnsEncryptionMode.AES_256_CBC,
            sendKey = key1, recvKey = key2, isInitiator = true,
        )
        val receiver = RnsLink(
            id = ByteArray(16), destHash = ByteArray(16),
            state = RnsLinkState.ACTIVE,
            encryptionMode = RnsEncryptionMode.AES_256_CBC,
            sendKey = key2, recvKey = key1, isInitiator = false,
        )

        val plaintext = "hello CBC mode".toByteArray()
        val encrypted = sender.encrypt(plaintext)
        val decrypted = receiver.decrypt(encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `GCM ciphertext has nonce prefix`() {
        val link = RnsLink(
            id = ByteArray(16), destHash = ByteArray(16),
            state = RnsLinkState.ACTIVE,
            encryptionMode = RnsEncryptionMode.AES_256_GCM,
            sendKey = ByteArray(32) { it.toByte() },
            recvKey = ByteArray(32), isInitiator = true,
        )
        val ct = link.encrypt("test".toByteArray())
        // GCM: 12 nonce + 4 plaintext + 16 tag = 32
        assertTrue(ct.size >= 12 + 4 + 16)
    }

    @Test
    fun `CBC ciphertext has IV prefix`() {
        val link = RnsLink(
            id = ByteArray(16), destHash = ByteArray(16),
            state = RnsLinkState.ACTIVE,
            encryptionMode = RnsEncryptionMode.AES_256_CBC,
            sendKey = ByteArray(32) { it.toByte() },
            recvKey = ByteArray(32), isInitiator = true,
        )
        val ct = link.encrypt("test".toByteArray())
        // CBC: 16 IV + 16 block (PKCS5 padded) = 32
        assertTrue(ct.size >= 16 + 16)
    }

    @Test
    fun `sequential GCM encryptions produce different ciphertexts`() {
        val link = RnsLink(
            id = ByteArray(16), destHash = ByteArray(16),
            state = RnsLinkState.ACTIVE,
            encryptionMode = RnsEncryptionMode.AES_256_GCM,
            sendKey = ByteArray(32) { it.toByte() },
            recvKey = ByteArray(32), isInitiator = true,
        )
        val ct1 = link.encrypt("same".toByteArray())
        val ct2 = link.encrypt("same".toByteArray())
        assertFalse(ct1.contentEquals(ct2))  // different nonces
    }

    // --- Wire sizes ---

    @Test
    fun `link request fits in MTU`() {
        // LINKREQUEST: header(18) + data(67) = 85 bytes
        val packet = RnsPacket.linkRequest(ByteArray(16), ByteArray(RnsLinkRequest.SIZE))
        assertTrue(RnsPacket.validateSize(packet))
        assertEquals(85, packet.wireSize())
    }

    @Test
    fun `link proof fits in MTU`() {
        // PROOF: header(18) + data(99) = 117 bytes
        val packet = RnsPacket.proof(ByteArray(16), ByteArray(RnsLinkProof.SIZE))
        assertTrue(RnsPacket.validateSize(packet))
        assertEquals(117, packet.wireSize())
    }

    @Test
    fun `total handshake fits in Iridium SBD`() {
        // 85 + 117 = 202 bytes total (fits in 340-byte SBD)
        assertTrue(85 + 117 <= 340)
    }

    // --- Constants ---

    @Test
    fun `link ID is 16 bytes (truncated hash)`() {
        assertEquals(16, RnsLink.LINK_ID_LEN)
    }

    @Test
    fun `link request size is 67`() {
        assertEquals(67, RnsLinkRequest.SIZE)
    }

    @Test
    fun `link proof size is 99`() {
        assertEquals(99, RnsLinkProof.SIZE)
    }
}
