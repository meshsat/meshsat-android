package com.cubeos.meshsat

import com.cubeos.meshsat.crypto.KeyBundleImporter
import com.cubeos.meshsat.crypto.KeyBundleImporter.ImportResult
import com.cubeos.meshsat.crypto.KeyBundleImporter.TrustStatus
import com.cubeos.meshsat.data.BridgeTrustDao
import com.cubeos.meshsat.data.BridgeTrustEntity
import com.cubeos.meshsat.data.ConversationKey
import com.cubeos.meshsat.data.ConversationKeyDao
import com.cubeos.meshsat.data.ProviderCredential
import com.cubeos.meshsat.data.ProviderCredentialDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.util.Base64

/**
 * Unit tests for KeyBundleImporter TOFU key pinning (MESHSAT-495).
 *
 * Tests cover: valid v1/v2, forged signature, tampered entries, pubkey
 * mismatch (key rotation), and forced re-pin.
 */
class KeyBundleImporterTest {

    @Before
    fun setup() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ------------- Fake DAOs (in-memory) ----------------

    private class FakeBridgeTrustDao : BridgeTrustDao {
        val store = mutableMapOf<String, BridgeTrustEntity>()

        override suspend fun get(hash: String) = store[hash]
        override suspend fun getAll() = store.values.sortedByDescending { it.lastSeen }
        override suspend fun upsert(entity: BridgeTrustEntity) { store[entity.bridgeHash] = entity }
        override suspend fun delete(hash: String) { store.remove(hash) }
        override suspend fun count() = store.size
    }

    private class FakeConversationKeyDao : ConversationKeyDao {
        val store = mutableMapOf<String, ConversationKey>()

        override suspend fun upsert(key: ConversationKey) { store[key.sender] = key }
        override suspend fun getBySender(sender: String): ConversationKey? = store[sender]
        override fun getAll(): Flow<List<ConversationKey>> = flowOf(store.values.toList())
        override suspend fun deleteBySender(sender: String) { store.remove(sender) }
    }

    private class FakeProviderCredentialDao : ProviderCredentialDao {
        val store = mutableMapOf<String, ProviderCredential>()

        override suspend fun upsert(credential: ProviderCredential) { store[credential.id] = credential }
        override fun getAll(): Flow<List<ProviderCredential>> = flowOf(store.values.toList())
        override suspend fun getById(id: String) = store[id]
        override suspend fun getByProvider(provider: String): List<ProviderCredential> =
            store.values.filter { it.provider == provider }
        override suspend fun deleteById(id: String) { store.remove(id) }
        override suspend fun count() = store.size
    }

    // ------------- Bundle builder (mirrors Go MarshalBundle / MarshalBundleV2) -----

    private data class TestEntry(val channelType: Byte, val address: String, val key: ByteArray)

    private fun buildV1Bundle(
        bridgeHash: ByteArray,
        entries: List<TestEntry>,
        privateKey: PrivateKey,
    ): ByteArray {
        val header = ByteArray(22)
        header[0] = 0x01 // v1
        System.arraycopy(bridgeHash, 0, header, 1, 16)
        val ts = (System.currentTimeMillis() / 1000).toInt()
        ByteBuffer.wrap(header, 17, 4).order(ByteOrder.BIG_ENDIAN).putInt(ts)
        header[21] = entries.size.toByte()

        val entryData = marshalEntries(entries)

        val sigData = header + entryData
        val sig = ed25519Sign(privateKey, sigData)

        return header + sig + entryData
    }

    private fun buildV2Bundle(
        bridgeHash: ByteArray,
        entries: List<TestEntry>,
        privateKey: PrivateKey,
        publicKey: ByteArray,
    ): ByteArray {
        val header = ByteArray(22)
        header[0] = 0x02 // v2
        System.arraycopy(bridgeHash, 0, header, 1, 16)
        val ts = (System.currentTimeMillis() / 1000).toInt()
        ByteBuffer.wrap(header, 17, 4).order(ByteOrder.BIG_ENDIAN).putInt(ts)
        header[21] = entries.size.toByte()

        val entryData = marshalEntries(entries)

        // signed data: header + pubkey + entries
        val sigData = header + publicKey + entryData
        val sig = ed25519Sign(privateKey, sigData)

        // wire: header + pubkey + signature + entries
        return header + publicKey + sig + entryData
    }

    private fun marshalEntries(entries: List<TestEntry>): ByteArray {
        val buf = mutableListOf<Byte>()
        for (e in entries) {
            val addr = e.address.toByteArray(Charsets.UTF_8)
            buf.add(e.channelType)
            buf.add(addr.size.toByte())
            buf.addAll(addr.toList())
            buf.addAll(e.key.toList())
        }
        return buf.toByteArray()
    }

    private fun ed25519Sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519", "BC")
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    private fun generateEd25519Keypair(): Pair<PrivateKey, ByteArray> {
        val kp = KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
        val pubEncoded = kp.public.encoded
        val rawPub = if (pubEncoded.size == 44) pubEncoded.copyOfRange(12, 44) else pubEncoded
        return kp.private to rawPub
    }

    private fun bundleToUrl(data: ByteArray): String =
        "meshsat://key/" + Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private fun randomBridgeHash(): ByteArray {
        val h = ByteArray(16)
        java.security.SecureRandom().nextBytes(h)
        return h
    }

    private fun randomAesKey(): ByteArray {
        val k = ByteArray(32)
        java.security.SecureRandom().nextBytes(k)
        return k
    }

    private fun testEntries() = listOf(
        TestEntry(0x00, "+31612345678", randomAesKey()),
        TestEntry(0x01, "!abc12345", randomAesKey()),
    )

    // ------------- Tests ----------------

    @Test
    fun `v2 valid bundle - first import pins key`() = runBlocking {
        val (priv, pub) = generateEd25519Keypair()
        val bh = randomBridgeHash()
        val entries = testEntries()
        val data = buildV2Bundle(bh, entries, priv, pub)
        val url = bundleToUrl(data)

        val trustDao = FakeBridgeTrustDao()
        val convDao = FakeConversationKeyDao()
        val credDao = FakeProviderCredentialDao()

        val result = KeyBundleImporter.importFromURL(url, trustDao, convDao, credDao)

        assertTrue("Expected Success, got $result", result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(2, success.count)
        assertEquals(TrustStatus.NEW_TRUSTED, success.status)
        assertEquals(1, trustDao.store.size)
        assertTrue(trustDao.store.values.first().pubkey.contentEquals(pub))
        assertEquals(2, convDao.store.size)
        assertEquals(2, credDao.store.size)
    }

    @Test
    fun `v2 valid bundle - second import verifies against pin`() = runBlocking {
        val (priv, pub) = generateEd25519Keypair()
        val bh = randomBridgeHash()
        val entries = testEntries()
        val data = buildV2Bundle(bh, entries, priv, pub)
        val url = bundleToUrl(data)

        val trustDao = FakeBridgeTrustDao()
        val convDao = FakeConversationKeyDao()
        val credDao = FakeProviderCredentialDao()

        // First import
        KeyBundleImporter.importFromURL(url, trustDao, convDao, credDao)
        // Second import
        val result = KeyBundleImporter.importFromURL(url, trustDao, convDao, credDao)

        assertTrue("Expected Success, got $result", result is ImportResult.Success)
        assertEquals(TrustStatus.EXISTING_TRUSTED, (result as ImportResult.Success).status)
        assertEquals(2, trustDao.store.values.first().importCount)
    }

    @Test
    fun `v2 forged signature - rejected`() = runBlocking {
        val (priv, pub) = generateEd25519Keypair()
        val bh = randomBridgeHash()
        val data = buildV2Bundle(bh, testEntries(), priv, pub)

        // Tamper the signature (byte 55 is inside the signature for v2)
        data[55] = (data[55].toInt() xor 0xFF).toByte()

        val url = bundleToUrl(data)
        val trustDao = FakeBridgeTrustDao()

        val result = KeyBundleImporter.importFromURL(url, trustDao, FakeConversationKeyDao(), FakeProviderCredentialDao())

        assertTrue("Expected InvalidSignature, got $result", result is ImportResult.InvalidSignature)
        assertEquals(0, trustDao.store.size)
    }

    @Test
    fun `v2 tampered entry data - rejected`() = runBlocking {
        val (priv, pub) = generateEd25519Keypair()
        val data = buildV2Bundle(randomBridgeHash(), testEntries(), priv, pub)

        // Tamper with last byte (entry data)
        data[data.size - 1] = (data[data.size - 1].toInt() xor 0xFF).toByte()

        val url = bundleToUrl(data)
        val result = KeyBundleImporter.importFromURL(
            url, FakeBridgeTrustDao(), FakeConversationKeyDao(), FakeProviderCredentialDao()
        )

        assertTrue("Expected InvalidSignature, got $result", result is ImportResult.InvalidSignature)
    }

    @Test
    fun `v2 swapped pubkey - signature fails`() = runBlocking {
        val (priv, pub) = generateEd25519Keypair()
        val (_, otherPub) = generateEd25519Keypair()
        val data = buildV2Bundle(randomBridgeHash(), testEntries(), priv, pub)

        // Replace embedded pubkey with different one (bytes 22..54)
        System.arraycopy(otherPub, 0, data, 22, 32)

        val url = bundleToUrl(data)
        val result = KeyBundleImporter.importFromURL(
            url, FakeBridgeTrustDao(), FakeConversationKeyDao(), FakeProviderCredentialDao()
        )

        assertTrue("Expected InvalidSignature, got $result", result is ImportResult.InvalidSignature)
    }

    @Test
    fun `v2 pubkey rotation without force - KeyMismatch`() = runBlocking {
        val (priv1, pub1) = generateEd25519Keypair()
        val (priv2, pub2) = generateEd25519Keypair()
        val bh = randomBridgeHash()

        val trustDao = FakeBridgeTrustDao()
        val convDao = FakeConversationKeyDao()
        val credDao = FakeProviderCredentialDao()

        // First import with key 1
        val url1 = bundleToUrl(buildV2Bundle(bh, testEntries(), priv1, pub1))
        KeyBundleImporter.importFromURL(url1, trustDao, convDao, credDao)

        // Second import with different key
        val url2 = bundleToUrl(buildV2Bundle(bh, testEntries(), priv2, pub2))
        val result = KeyBundleImporter.importFromURL(url2, trustDao, convDao, credDao)

        assertTrue("Expected KeyMismatch, got $result", result is ImportResult.KeyMismatch)
        val mismatch = result as ImportResult.KeyMismatch
        assertTrue(mismatch.storedPubkey.contentEquals(pub1))
        assertTrue(mismatch.presentedPubkey.contentEquals(pub2))
    }

    @Test
    fun `v2 pubkey rotation with force - re-pins`() = runBlocking {
        val (priv1, pub1) = generateEd25519Keypair()
        val (priv2, pub2) = generateEd25519Keypair()
        val bh = randomBridgeHash()

        val trustDao = FakeBridgeTrustDao()
        val convDao = FakeConversationKeyDao()
        val credDao = FakeProviderCredentialDao()

        // First import with key 1
        val url1 = bundleToUrl(buildV2Bundle(bh, testEntries(), priv1, pub1))
        KeyBundleImporter.importFromURL(url1, trustDao, convDao, credDao)

        // Force re-pin with different key
        val url2 = bundleToUrl(buildV2Bundle(bh, testEntries(), priv2, pub2))
        val result = KeyBundleImporter.importFromURL(url2, trustDao, convDao, credDao, forceRepin = true)

        assertTrue("Expected Success, got $result", result is ImportResult.Success)
        assertEquals(TrustStatus.NEW_TRUSTED, (result as ImportResult.Success).status)
        assertTrue(trustDao.store.values.first().pubkey.contentEquals(pub2))
    }

    @Test
    fun `v1 bundle - imported as unverified`() = runBlocking {
        val (priv, _) = generateEd25519Keypair()
        val data = buildV1Bundle(randomBridgeHash(), testEntries(), priv)
        val url = bundleToUrl(data)

        val trustDao = FakeBridgeTrustDao()
        val result = KeyBundleImporter.importFromURL(
            url, trustDao, FakeConversationKeyDao(), FakeProviderCredentialDao()
        )

        assertTrue("Expected Success, got $result", result is ImportResult.Success)
        assertEquals(TrustStatus.UNVERIFIED_V1, (result as ImportResult.Success).status)
        assertEquals(0, trustDao.store.size) // v1 does NOT create trust record
    }

    @Test
    fun `malformed URL - rejected`() = runBlocking {
        val result = KeyBundleImporter.importFromURL(
            "https://example.com",
            FakeBridgeTrustDao(), FakeConversationKeyDao(), FakeProviderCredentialDao()
        )
        assertTrue("Expected Malformed, got $result", result is ImportResult.Malformed)
    }

    @Test
    fun `truncated bundle - rejected`() = runBlocking {
        val url = bundleToUrl(ByteArray(10))
        val result = KeyBundleImporter.importFromURL(
            url, FakeBridgeTrustDao(), FakeConversationKeyDao(), FakeProviderCredentialDao()
        )
        assertTrue("Expected Malformed, got $result", result is ImportResult.Malformed)
    }

    @Test
    fun `unsupported version - rejected`() = runBlocking {
        val data = ByteArray(120)
        data[0] = 0x03 // unsupported version
        val url = bundleToUrl(data)
        val result = KeyBundleImporter.importFromURL(
            url, FakeBridgeTrustDao(), FakeConversationKeyDao(), FakeProviderCredentialDao()
        )
        assertTrue("Expected Malformed, got $result", result is ImportResult.Malformed)
    }
}
